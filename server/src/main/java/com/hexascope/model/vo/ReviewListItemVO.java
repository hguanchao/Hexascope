/*
 * 文件说明：接口出参 VO，定义页面展示和接口响应需要的数据结构。
 */
package com.hexascope.model.vo;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 审查列表项。
 */
public record ReviewListItemVO(
        String id,
        String requirementId,
        String requirementTitle,
        String requirementDescription,
        String priority,
        String teamId,
        String creator,
        Integer totalScore,
        Map<String, Integer> dimensions,
        String level,
        String status,
        String reviewedBy,
        LocalDateTime createdAt,
        LocalDateTime completedAt
) implements Serializable {
}
