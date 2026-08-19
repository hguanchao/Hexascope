/*
 * 文件说明：接口出参 VO，定义页面展示和接口响应需要的数据结构。
 */
package com.hexascope.model.vo;

import java.io.Serializable;
import java.util.Map;

/**
 * 团队统计数据。
 */
public record TeamStatsVO(
        Double avgScore,
        Double passRate,
        Long pendingCount,
        Long totalReviewed,
        Map<String, Double> dimensionAvg
) implements Serializable {
}
