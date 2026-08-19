/*
 * 文件说明：知识库混合检索服务，融合 pgvector 向量召回与 pg_trgm 关键词召回。
 */
package com.hexascope.ai.kb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库混合检索服务。
 *
 * <p>对同一条检索 query 同时走两条路：pgvector 向量相似度召回 + pg_trgm 关键词召回，
 * 再用 RRF（默认）或加权和融合去重，返回统一的候选文档列表。
 *
 * <p>任何一条路的异常都不会中断检索：向量路失败只用关键词路，关键词路失败只用向量路；
 * 两条路都失败时抛出异常，由调用方走“通用评分标准”兜底。
 * 关闭开关（{@code hexascope.kb.hybrid.enabled=false}）时行为与旧版一致，只走向量检索。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridSearchService {

    /**
     * 关键词检索 SQL。
     *
     * <p>{@code %s} 位置插入 metadata 过滤条件（参数绑定方式），
     * 其余占位符按顺序对应：content 截断长度、关键词、ILIKE 模式、
     * content 截断长度、关键词、最小相似度、ILIKE 模式、返回数量。</p>
     */
    private static final String KEYWORD_SCAN_SQL = """
            SELECT id::text AS id,
                   content,
                   metadata::text AS metadata_json,
                   (word_similarity(LEFT(content, ?), ?)
                        + CASE WHEN content ILIKE ? THEN 0.2 ELSE 0.0 END) AS k_score
            FROM vector_store
            WHERE content IS NOT NULL AND content <> ''
              %s
              AND (word_similarity(LEFT(content, ?), ?) > ? OR content ILIKE ?)
            ORDER BY k_score DESC
            LIMIT ?
            """;

    /**
     * RRF 之外的融合方式取值。
     */
    private static final String FUSION_WEIGHTED = "weighted";

    private final VectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final HybridSearchProperties properties;

    /**
     * 混合检索入口（按全局配置决定是否启用关键词路）。
     *
     * @param query              检索 query
     * @param topK               调用方期望返回的候选数量（融合后按此截断）
     * @param similarityThreshold 向量路相似度阈值
     * @param filter             知识库过滤条件，可为空
     * @return 融合去重后的候选文档
     */
    public List<Document> search(String query, int topK, double similarityThreshold, KbFilter filter) {
        return search(query, topK, similarityThreshold, filter, properties.isEnabled());
    }

    /**
     * 混合检索入口（显式指定是否启用关键词路）。
     *
     * <p>评估服务用它分别在“纯向量 / 混合”两种配置下跑召回，做指标对比；
     * 业务检索走不带开关的重载，行为由全局配置控制。</p>
     */
    public List<Document> search(String query, int topK, double similarityThreshold, KbFilter filter,
                                 boolean hybridEnabled) {
        KbFilter safeFilter = filter != null ? filter : KbFilter.EMPTY;
        if (!hybridEnabled) {
            return vectorSearch(query, topK, similarityThreshold, safeFilter);
        }

        List<Candidate> vectorCandidates = safeVectorCandidates(query, topK, similarityThreshold, safeFilter);
        List<Candidate> keywordCandidates = safeKeywordCandidates(query, safeFilter);

        if (vectorCandidates.isEmpty() && keywordCandidates.isEmpty()) {
            throw new IllegalStateException("混合检索两路均失败");
        }

        List<Candidate> fused = fuse(vectorCandidates, keywordCandidates, topK,
                properties.getFusion(), properties.getRrfK(),
                properties.getVectorWeight(), properties.getKeywordWeight());
        return toDocuments(fused);
    }

    /**
     * 当前是否启用混合检索（供打点与评估对比使用）。
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 向量路检索（可指定过滤表达式）。
     */
    private List<Document> vectorSearch(String query, int topK, double similarityThreshold, KbFilter filter) {
        int fetchTop = Math.max(topK, properties.getVectorTop());
        SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(fetchTop)
                .similarityThreshold(similarityThreshold);
        if (!filter.isEmpty()) {
            builder.filterExpression(filter.toFilterExpression());
        }
        return vectorStore.similaritySearch(builder.build());
    }

    /**
     * 向量路安全包装：失败时降级为空列表，由融合逻辑决定是否只用关键词路。
     */
    private List<Candidate> safeVectorCandidates(String query, int topK, double similarityThreshold, KbFilter filter) {
        try {
            return vectorSearch(query, topK, similarityThreshold, filter).stream()
                    .map(document -> new Candidate(
                            document.getId(),
                            document.getText(),
                            document.getMetadata(),
                            document.getScore()))
                    .toList();
        } catch (Exception e) {
            log.warn("混合检索向量路失败，改用关键词路: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词路安全包装：失败时降级为空列表，由融合逻辑决定是否只用向量路。
     */
    private List<Candidate> safeKeywordCandidates(String query, KbFilter filter) {
        try {
            return keywordSearch(query, filter);
        } catch (Exception e) {
            log.warn("混合检索关键词路失败，改用向量路: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 关键词路检索（pg_trgm）。
     *
     * <p>query 先截断到配置长度（trigram 相似度对超长 query 区分度差，
     * 维度 query 的维度名在开头因此能保住关键信息），content 只取前 N 字符参与比对。</p>
     */
    List<Candidate> keywordSearch(String query, KbFilter filter) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.length() > properties.getKeywordQueryMaxChars()) {
            keyword = keyword.substring(0, properties.getKeywordQueryMaxChars());
        }
        if (keyword.isEmpty()) {
            return List.of();
        }

        List<Object> params = new ArrayList<>();
        params.add(properties.getKeywordContentMaxChars());
        params.add(keyword);
        params.add("%" + keyword + "%");

        String metadataFilterSql = buildMetadataFilterSql(filter, params);

        params.add(properties.getKeywordContentMaxChars());
        params.add(keyword);
        params.add(properties.getMinKeywordScore());
        params.add("%" + keyword + "%");
        params.add(properties.getKeywordTop());

        String sql = String.format(KEYWORD_SCAN_SQL, metadataFilterSql);
        List<Candidate> candidates = jdbcTemplate.query(sql, (rs, rowNum) -> new Candidate(
                rs.getString("id"),
                rs.getString("content"),
                parseMetadata(rs.getString("metadata_json")),
                rs.getDouble("k_score")
        ), params.toArray());

        log.debug("关键词检索完成: query={}, hits={}", keyword, candidates.size());
        return candidates;
    }

    /**
     * 把过滤条件拼成参数绑定的 WHERE 子句。
     *
     * <p>同一键多值为 OR，不同键之间为 AND；值全部走占位符，避免注入。</p>
     */
    private String buildMetadataFilterSql(KbFilter filter, List<Object> params) {
        if (filter == null || filter.isEmpty()) {
            return "";
        }
        List<String> conditions = new ArrayList<>();
        for (KbFilter.SqlCondition condition : filter.sqlConditions()) {
            List<String> ors = new ArrayList<>();
            for (String value : condition.values()) {
                ors.add("(metadata ->> ?) = ?");
                params.add(condition.key());
                params.add(value);
            }
            conditions.add("(" + String.join(" OR ", ors) + ")");
        }
        return "AND " + String.join(" AND ", conditions);
    }

    /**
     * 融合两路候选。
     *
     * <p>RRF：对两路排名分别按 1/(k+rank) 计分后求和，与两路分数口径无关；
     * 加权和：需要两路都提供可比的 0~1 分数，只命中一路时另一路按 0 计。</p>
     */
    static List<Candidate> fuse(List<Candidate> vectorCandidates, List<Candidate> keywordCandidates,
                                int limit, String mode, int rrfK,
                                double vectorWeight, double keywordWeight) {
        if (vectorCandidates.isEmpty()) {
            return cap(keywordCandidates, limit);
        }
        if (keywordCandidates.isEmpty()) {
            return cap(vectorCandidates, limit);
        }

        Map<String, Candidate> union = new LinkedHashMap<>();
        for (Candidate candidate : vectorCandidates) {
            union.putIfAbsent(candidate.id(), candidate);
        }
        for (Candidate candidate : keywordCandidates) {
            union.putIfAbsent(candidate.id(), candidate);
        }

        Map<String, Double> scores = new HashMap<>();
        if (FUSION_WEIGHTED.equalsIgnoreCase(mode)) {
            Map<String, Double> vectorScores = candidateScores(vectorCandidates);
            Map<String, Double> keywordScores = candidateScores(keywordCandidates);
            for (String id : union.keySet()) {
                scores.put(id, vectorWeight * vectorScores.getOrDefault(id, 0.0)
                        + keywordWeight * keywordScores.getOrDefault(id, 0.0));
            }
        } else {
            for (int i = 0; i < vectorCandidates.size(); i++) {
                scores.merge(vectorCandidates.get(i).id(), 1.0 / (rrfK + i + 1), Double::sum);
            }
            for (int i = 0; i < keywordCandidates.size(); i++) {
                scores.merge(keywordCandidates.get(i).id(), 1.0 / (rrfK + i + 1), Double::sum);
            }
        }

        List<String> ids = new ArrayList<>(union.keySet());
        ids.sort(Comparator
                .comparingDouble((String id) -> scores.getOrDefault(id, 0.0)).reversed()
                .thenComparingInt(ids::indexOf));
        return ids.stream()
                .limit(Math.max(0, limit))
                .map(union::get)
                .toList();
    }

    /**
     * 提取候选的分数映射，缺失分数按 0 处理。
     */
    private static Map<String, Double> candidateScores(List<Candidate> candidates) {
        Map<String, Double> scores = new HashMap<>();
        for (Candidate candidate : candidates) {
            scores.put(candidate.id(), candidate.score() != null ? candidate.score() : 0.0);
        }
        return scores;
    }

    /**
     * 按数量截断候选列表。
     */
    private static List<Candidate> cap(List<Candidate> candidates, int limit) {
        return candidates.stream().limit(Math.max(0, limit)).toList();
    }

    /**
     * 把内部候选转回 Spring AI Document。
     */
    private List<Document> toDocuments(List<Candidate> candidates) {
        return candidates.stream()
                .map(candidate -> new Document(
                        candidate.id(),
                        candidate.text() != null ? candidate.text() : "",
                        candidate.metadata() != null ? candidate.metadata() : Map.of()))
                .toList();
    }

    /**
     * 解析 metadata JSON，异常时返回空 Map 以保证检索不中断。
     */
    private Map<String, Object> parseMetadata(String metadataJson) {
        if (!StringUtils.hasText(metadataJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("解析知识片段 metadata 失败，按空元数据处理: {}", e.getMessage());
            return Map.of();
        }
    }

    /**
     * 融合前的内部候选表示，避免提前构造 Document。
     *
     * @param id       向量表主键
     * @param text     知识片段内容
     * @param metadata 元数据（维度/来源/等级等）
     * @param score    该路的原始分数，可为 null
     */
    record Candidate(String id, String text, Map<String, Object> metadata, Double score) {
    }
}