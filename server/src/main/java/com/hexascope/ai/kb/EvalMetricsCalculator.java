/*
 * 文件说明：知识库召回评估指标计算，纯逻辑便于单元测试。
 */
package com.hexascope.ai.kb;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 知识库召回评估指标计算。
 *
 * <p>以“检索结果顺序”为基准计算：recall@k = 命中数 / 期望数；
 * precision@k = 命中数 / 返回数；MRR = 首个命中位置倒数（无命中为 0）。</p>
 *
 * @author Hexascope Team
 */
public final class EvalMetricsCalculator {

    private EvalMetricsCalculator() {
    }

    /**
     * 计算单次检索的评估指标。
     *
     * @param retrievedIds 检索返回的文档 ID（按相关度降序）
     * @param expectedIds  期望命中的文档 ID（金标）
     * @return 指标结果
     */
    public static EvalMetrics compute(List<String> retrievedIds, List<String> expectedIds) {
        List<String> retrieved = retrievedIds != null ? retrievedIds : List.of();
        List<String> expected = expectedIds != null ? expectedIds : List.of();
        if (expected.isEmpty()) {
            return new EvalMetrics(0.0, 0.0, 0.0, 0, 0, retrieved.size(), 0);
        }

        Set<String> expectedSet = new HashSet<>(expected);
        int hits = 0;
        for (String id : retrieved) {
            if (expectedSet.contains(id)) {
                hits++;
            }
        }

        double recall = (double) hits / expected.size();
        double precision = retrieved.isEmpty() ? 0 : (double) hits / retrieved.size();
        int firstHitRank = 0;
        for (int i = 0; i < retrieved.size(); i++) {
            if (expectedSet.contains(retrieved.get(i))) {
                firstHitRank = i + 1;
                break;
            }
        }
        double mrr = firstHitRank > 0 ? 1.0 / firstHitRank : 0.0;

        return new EvalMetrics(round(recall), round(precision), round(mrr),
                hits, expected.size(), retrieved.size(), firstHitRank);
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    /**
     * 单次检索指标。
     *
     * @param recall            召回率
     * @param precision         精确率
     * @param mrr               首个命中位置倒数
     * @param hitCount          命中数
     * @param expectedCount     期望数
     * @param retrievedCount    返回数
     * @param firstHitRank      首个命中位置（1 起），无命中为 0
     */
    public record EvalMetrics(double recall, double precision, double mrr,
                              int hitCount, int expectedCount, int retrievedCount,
                              int firstHitRank) {
    }
}