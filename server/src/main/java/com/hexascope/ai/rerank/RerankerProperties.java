/*
 * 文件说明：RAG 重排组件，负责调用重排服务提升评分知识命中的相关性。
 */
package com.hexascope.ai.rerank;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Reranker 重排序配置。
 *
 * <p>用于控制 pgvector 粗召回后的二次排序，不影响向量表结构。
 * 配置开关关闭或密钥为空时，系统会自动退回到原来的 pgvector 排序结果。</p>
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hexascope.reranker")
public class RerankerProperties {

    /**
     * 是否启用重排序。
     *
     * <p>true：pgvector 先召回候选评分标准，再调用 reranker 精排；
     * false：保持原有 pgvector 检索结果，不调用外部重排序接口。</p>
     */
    private boolean enabled = false;

    /**
     * 重排序服务地址。
     *
     * <p>硅基流动接口的根地址，客户端会自动拼接 /v1/rerank。</p>
     */
    private String baseUrl = "https://api.siliconflow.cn";

    /**
     * 重排序接口密钥。
     *
     * <p>当前默认复用 embedding 的硅基流动密钥，避免维护两份相同配置。</p>
     */
    private String apiKey;

    /**
     * 重排序模型。
     *
     * <p>该模型不生成向量，只负责判断“需求”和“候选评分标准”谁更相关。</p>
     */
    private String model = "BAAI/bge-reranker-v2-m3";

    /**
     * pgvector 粗召回数量。
     *
     * <p>数量要大于最终进入 Prompt 的 topN，这样 reranker 才有足够候选可以重排。</p>
     */
    private int candidateK = 20;

    /**
     * 重排序后保留数量。
     *
     * <p>最终只有这些评分标准会进入 Prompt，数量太多会增加大模型上下文噪声。</p>
     */
    private int topN = 8;

    /**
     * 长文档内部切块上限。
     *
     * <p>单条评分标准较长时，reranker 会在内部切块后取最高相关性。</p>
     */
    private int maxChunksPerDoc = 1024;

    /**
     * 文档切块时相邻块重叠 token 数。
     *
     * <p>重叠可以减少切块边界造成的语义割裂；评分标准较短时影响很小。</p>
     */
    private Integer overlapTokens = 20;
}
