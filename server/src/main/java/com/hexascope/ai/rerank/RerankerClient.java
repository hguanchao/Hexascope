/*
 * 文件说明：RAG 重排组件，负责调用重排服务提升评分知识命中的相关性。
 */
package com.hexascope.ai.rerank;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 硅基流动 Reranker 客户端。
 *
 * <p>先由 pgvector 找到候选评分标准，再由该客户端根据当前需求做二次排序。
 * 这个客户端只负责“重排候选文档”，不负责生成向量，也不改数据库。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerankerClient {

    /**
     * 硅基流动重排序接口路径。
     *
     * <p>最终请求地址 = baseUrl + RERANK_PATH。</p>
     */
    private static final String RERANK_PATH = "/v1/rerank";

    /**
     * HTTP 客户端，项目里统一由 WebMvcConfig 提供。
     */
    private final RestTemplate restTemplate;

    /**
     * Reranker 相关配置，来自 hexascope.reranker。
     */
    private final RerankerProperties properties;

    /**
     * 判断是否真正启用 reranker。
     *
     * <p>除了配置开关为 true，还必须有 apiKey；否则直接走 pgvector 原始结果。</p>
     */
    public boolean isEnabled() {
        return properties.isEnabled() && StrUtil.isNotBlank(properties.getApiKey());
    }

    /**
     * 计算 pgvector 粗召回数量。
     *
     * <p>启用 reranker 时需要先多召回一些候选，例如 20 条，再精排出 8 条。
     * 未启用时保持原来的 topK，避免无意义地扩大查询范围。</p>
     */
    public int candidateLimit(int defaultTopK) {
        if (!isEnabled()) {
            return defaultTopK;
        }
        return Math.max(properties.getCandidateK(), properties.getTopN());
    }

    /**
     * 对 pgvector 候选结果做二次排序。
     *
     * <p>任何异常都不会中断审查主流程，而是回退为 pgvector 原始排序结果。</p>
     */
    public List<Document> rerank(String query, List<Document> documents) {
        if (!isEnabled() || StrUtil.isBlank(query) || CollUtil.isEmpty(documents)) {
            return documents;
        }

        // reranker 只能处理有文本内容的候选文档，空文档直接过滤。
        List<Document> candidates = documents.stream()
                .filter(document -> document != null && StrUtil.isNotBlank(document.getText()))
                .toList();
        if (CollUtil.isEmpty(candidates)) {
            return documents;
        }

        try {
            RerankResponse response = callRerank(query, candidates);
            List<Document> reranked = toRerankedDocuments(response, candidates);
            if (CollUtil.isEmpty(reranked)) {
                log.warn("Reranker 返回结果为空，使用 pgvector 原始排序");
                return limit(documents, properties.getTopN());
            }
            log.info("Reranker 精排完成: candidates={}, selected={}", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Reranker 调用失败，使用 pgvector 原始排序: {}", e.getMessage());
            return limit(documents, properties.getTopN());
        }
    }

    private RerankResponse callRerank(String query, List<Document> candidates) {
        // 硅基流动 rerank 接口使用 Bearer Token 鉴权。
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.getApiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 请求体格式参考硅基流动 /v1/rerank：query 是当前需求，documents 是候选评分标准。
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", properties.getModel());
        body.put("query", query);
        body.put("documents", candidates.stream().map(Document::getText).toList());
        // 不要求接口返回文档原文，减少响应体；后续通过 index 映射回本地 candidates。
        body.put("return_documents", false);
        body.put("top_n", Math.max(1, properties.getTopN()));
        body.put("max_chunks_per_doc", Math.max(1, properties.getMaxChunksPerDoc()));
        if (properties.getOverlapTokens() != null) {
            body.put("overlap_tokens", properties.getOverlapTokens());
        }

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        // 调用硅基流动接口后，Spring 会按 RerankResponse 结构反序列化 JSON 响应。
        ResponseEntity<RerankResponse> response = restTemplate.exchange(
                buildUrl(), HttpMethod.POST, request, RerankResponse.class
        );
        return response.getBody();
    }

    /**
     * 拼接完整重排序接口地址。
     */
    private String buildUrl() {
        return StrUtil.removeSuffix(properties.getBaseUrl(), "/") + RERANK_PATH;
    }

    /**
     * 将 reranker 的排序结果转换回 Spring AI Document。
     *
     * <p>reranker 返回的是候选数组下标，不直接返回项目里的 Document。
     * 因此这里要根据 index 回到 candidates 列表中取原始对象。</p>
     */
    private List<Document> toRerankedDocuments(RerankResponse response, List<Document> candidates) {
        if (response == null || CollUtil.isEmpty(response.getResults())) {
            return List.of();
        }

        List<Document> reranked = new ArrayList<>();
        Set<Integer> selectedIndexes = new HashSet<>();
        for (RerankResult result : response.getResults()) {
            // reranker 返回的是原 documents 数组下标，用下标取回原始 Document，保留 metadata 等信息。
            Integer index = result.getIndex();
            if (index == null || index < 0 || index >= candidates.size() || selectedIndexes.contains(index)) {
                continue;
            }
            reranked.add(candidates.get(index));
            selectedIndexes.add(index);
            if (reranked.size() >= properties.getTopN()) {
                break;
            }
        }
        return reranked;
    }

    /**
     * 按数量截断文档列表。
     *
     * <p>主要用于 reranker 不可用时的兜底逻辑。</p>
     */
    private List<Document> limit(List<Document> documents, int limit) {
        if (CollUtil.isEmpty(documents)) {
            return List.of();
        }
        // 回退时不重新排序，只截取 pgvector 原始排序靠前的若干条。
        return documents.stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * 硅基流动 rerank 响应体。
     *
     * <p>当前业务只关心 results 列表，其它 token 统计信息暂不使用。</p>
     */
    @Getter
    @Setter
    private static class RerankResponse {
        /**
         * 重排序结果，按相关性从高到低排列。
         */
        private List<RerankResult> results;
    }

    /**
     * 单条重排序结果。
     */
    @Getter
    @Setter
    private static class RerankResult {
        /**
         * 该结果在请求 documents 数组中的原始下标。
         */
        private Integer index;

        /**
         * 相关性分数，越大表示和当前需求越相关。
         *
         * <p>当前只按接口返回顺序取结果，暂不直接使用该分数。</p>
         */
        @JsonProperty("relevance_score")
        private Double relevanceScore;
    }
}
