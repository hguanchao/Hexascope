/*
 * 文件说明：AI 评分工作流节点进度监听接口。
 */
package com.hexascope.ai.workflow;

import com.hexascope.model.enums.ReviewWorkflowNode;

import java.util.Map;

/**
 * ReviewWorkflow 节点执行进度监听器。
 */
public interface ReviewWorkflowProgressListener {

    default void onNodeStart(ReviewWorkflowRequest request, ReviewWorkflowNode node,
                             Map<String, Object> snapshot) {
    }

    default void onNodeSuccess(ReviewWorkflowRequest request, ReviewWorkflowNode node,
                               Map<String, Object> snapshot) {
    }

    default void onNodeFailure(ReviewWorkflowRequest request, ReviewWorkflowNode node,
                               Map<String, Object> snapshot, Exception exception) {
    }
}
