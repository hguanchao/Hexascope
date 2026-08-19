/*
 * 文件说明：数据库实体模型，描述业务表字段与 Java 对象之间的映射。
 */
package com.hexascope.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hexascope.common.PostgresLocalDateTimeTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * AI 审查工作流断点实体
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "review_workflow_checkpoint", autoResultMap = true)
public class ReviewWorkflowCheckpoint {

    @TableId(type = IdType.INPUT)
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private UUID reviewId;

    private String requirementId;

    private String workflowInstanceId;

    private String currentNode;

    private String status;

    @TableField(typeHandler = com.hexascope.common.JsonbTypeHandler.class)
    private Map<String, Object> stateSnapshot;

    private String errorMessage;

    @Builder.Default
    private Integer resumeCount = 0;

    @TableField(fill = FieldFill.INSERT, typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE, typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime updatedAt;

    @TableField(typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime completedAt;
}
