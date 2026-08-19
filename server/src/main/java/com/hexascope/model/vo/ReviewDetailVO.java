/*
 * 文件说明：接口出参 VO，定义页面展示和接口响应需要的数据结构。
 */
package com.hexascope.model.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审查详情。
 */
public record ReviewDetailVO(
        String id,
        String requirementId,
        String requirementTitle,
        String requirementDescription,
        String priority,
        String requirementUrl,
        String teamId,
        String teamName,
        String creator,
        Integer totalScore,
        String level,
        String summary,
        String improvementSuggestion,
        String status,
        String reviewedBy,
        String aiModel,
        Integer aiLatencyMs,
        Integer retriggerCount,
        Map<String, DimensionDetail> dimensions,
        List<ReviewHistoryVO> history,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) implements Serializable {

    /**
     * 维度评分详情。
     */
    public record DimensionDetail(
            Integer score,
            Double weight,
            List<String> suggestions,
            List<String> evidence,
            List<String> missingItems,
            String scoreReason,
            Double confidence
    ) implements Serializable {
    }

    /**
     * 历史审查记录。
     */
    public record ReviewHistoryVO(
            Integer totalScore,
            String aiModel,
            String reason,
            LocalDateTime createdAt
    ) implements Serializable {
    }
}
