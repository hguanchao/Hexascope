/*
 * 文件说明：知识库混合检索配置，控制向量路与关键词路的召回和融合方式。
 */
package com.hexascope.ai.kb;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库混合检索配置。
 *
 * <p>混合检索 = pgvector 向量召回 + pg_trgm 关键词召回 + RRF/加权融合。
 * 关闭开关时行为与旧版一致（纯向量检索）。</p>
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hexascope.kb.hybrid")
public class HybridSearchProperties {

    /**
     * 是否启用混合检索。
     *
     * <p>false：保持原有 pgvector 向量检索，不追加关键词召回。</p>
     */
    private boolean enabled = true;

    /**
     * 向量路单条 query 的召回数量。
     *
     * <p>会取 max(调用方 topK, 该值)，让向量路多召回一些候选供融合。</p>
     */
    private int vectorTop = 30;

    /**
     * 关键词路单条 query 的召回数量。
     */
    private int keywordTop = 10;

    /**
     * 关键词路使用的 query 最大字符数。
     *
     * <p>检索 query 是模板拼出的长文本，trigram 相似度对超长 query 区分度差，
     * 这里截断到前 N 个字符（维度 query 的维度名在开头，能保留关键信息）。</p>
     */
    private int keywordQueryMaxChars = 300;

    /**
     * 关键词路参与相似度比对的 content 最大字符数。
     *
     * <p>pg_trgm 对超长文本区分度下降，截断到前 N 个字符参与比对。</p>
     */
    private int keywordContentMaxChars = 2048;

    /**
     * 关键词路最小相似度阈值，低于该值的候选不进入融合。
     */
    private double minKeywordScore = 0.05;

    /**
     * 融合方式：rrf（默认，对两路排名做倒数融合，无需统一分数口径）或 weighted（加权和）。
     */
    private String fusion = "rrf";

    /**
     * RRF 融合常数 k，控制排名权重衰减。
     */
    private int rrfK = 60;

    /**
     * 加权融合时向量路的权重。
     */
    private double vectorWeight = 0.6;

    /**
     * 加权融合时关键词路的权重。
     */
    private double keywordWeight = 0.4;
}