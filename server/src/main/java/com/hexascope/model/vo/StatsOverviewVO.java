/*
 * 文件说明：接口出参 VO，定义页面展示和接口响应需要的数据结构。
 */
package com.hexascope.model.vo;

import java.io.Serializable;
import java.util.List;

/**
 * 看板总览统计。
 */
public record StatsOverviewVO(
        Double avgScore,
        Double passRate,
        Long pendingCount,
        Long totalReviewed,
        ScoreDistribution scoreDistribution,
        List<TeamRanking> teamRanking
) implements Serializable {

    /**
     * 评分等级分布。
     */
    public record ScoreDistribution(
            Long excellent,
            Long good,
            Long warning,
            Long fail
    ) implements Serializable {
    }

    /**
     * 团队质量排名。
     */
    public record TeamRanking(
            String teamId,
            String teamName,
            Double avgScore,
            Long reviewCount,
            Double passRate
    ) implements Serializable {
    }
}
