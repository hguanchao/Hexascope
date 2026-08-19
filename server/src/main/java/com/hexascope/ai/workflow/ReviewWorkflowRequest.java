/*
 * 文件说明：AI 评分工作流入参，承载一次需求评分所需的稳定字段。
 */
package com.hexascope.ai.workflow;

import com.hexascope.model.entity.ReviewRecord;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * ReviewWorkflow 的稳定输入快照。
 *
 * <p>评分在事务提交后异步执行，因此不能依赖外部可变对象；这里把评分需要的
 * 需求字段复制成不可变 record，避免后台执行时读到不一致的数据。</p>
 *
 * @param reviewId 审查记录 ID
 * @param workflowInstanceId 工作流实例 ID
 * @param requirementId 需求业务 ID
 * @param title 需求标题
 * @param description 需求正文
 * @param priority 需求优先级
 * @param creator 创建人
 * @param kbFilters 知识库检索过滤条件（维度/来源/严重度），可为 null 表示不过滤
 */
public record ReviewWorkflowRequest(
        String reviewId,
        String workflowInstanceId,
        String requirementId,
        String title,
        String description,
        String priority,
        String creator,
        Map<String, List<String>> kbFilters
) implements Serializable {

    /**
     * 从数据库实体和工作流实例 ID 构造工作流输入，确保后台评分使用的是已落库记录。
     */
    public static ReviewWorkflowRequest from(ReviewRecord record, String workflowInstanceId) {
        return new ReviewWorkflowRequest(
                record.getId() != null ? record.getId().toString() : "",
                workflowInstanceId,
                record.getRequirementId(),
                record.getRequirementTitle(),
                record.getRequirementDescription(),
                record.getPriority(),
                record.getCreator(),
                record.getKbFilters()
        );
    }
}
