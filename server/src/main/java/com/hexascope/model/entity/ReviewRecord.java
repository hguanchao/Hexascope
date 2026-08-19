/*
 * 文件说明：数据库实体模型，描述业务表字段与 Java 对象之间的映射。
 */
package com.hexascope.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hexascope.common.PostgresLocalDateTimeTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 需求审查记录实体
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "review_record", autoResultMap = true)
public class ReviewRecord {

    @TableId(type = IdType.INPUT)
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private String requirementId;

    private String workspaceId;

    private String teamId;

    private String requirementTitle;

    private String requirementDescription;

    private String priority;

    private String requirementUrl;

    private String creator;

    private Integer totalScore;

    private String level;

    /**
     * 维度评分 JSON: {{@code {"completeness": 8, "clarity": 7, ...}}}
     */
    @TableField(typeHandler = com.hexascope.common.JsonbTypeHandler.class)
    private Map<String, Integer> dimensionScores;

    /**
     * AI 建议 JSON
     */
    @TableField(typeHandler = com.hexascope.common.JsonbTypeHandler.class)
    private Map<String, Object> aiSuggestions;

    /**
     * 知识库检索过滤条件 JSON: {{@code {"dimension": ["完整性"], "source": ["评分细则"]}}}，可为空
     */
    @TableField(typeHandler = com.hexascope.common.JsonbTypeHandler.class)
    private Map<String, List<String>> kbFilters;

    private String improvementSuggestion;

    private String status;

    private String reviewedBy;

    private String aiModelUsed;

    private Integer aiLatencyMs;

    private String rawPrompt;

    private String rawAiResponse;

    @Builder.Default
    private Integer retriggerCount = 0;

    @TableField(fill = FieldFill.INSERT, typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;

    @TableField(typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime completedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE, typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime updatedAt;

    @TableLogic
    @Builder.Default
    private Boolean isDeleted = false;
}
