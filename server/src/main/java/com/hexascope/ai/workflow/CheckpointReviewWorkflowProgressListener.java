/*
 * 文件说明：把 AI 评分工作流节点进度写入断点服务。
 */
package com.hexascope.ai.workflow;

import com.hexascope.model.enums.ReviewWorkflowNode;
import com.hexascope.service.WorkflowCheckpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 基于 WorkflowCheckpointService 的工作流进度监听器。
 */
@Component
@RequiredArgsConstructor
public class CheckpointReviewWorkflowProgressListener implements ReviewWorkflowProgressListener {

    private final WorkflowCheckpointService checkpointService;

    @Override
    public void onNodeStart(ReviewWorkflowRequest request, ReviewWorkflowNode node, Map<String, Object> snapshot) {
        checkpointService.markNodeRunning(requireWorkflowInstanceId(request), node, snapshot);
    }

    @Override
    public void onNodeSuccess(ReviewWorkflowRequest request, ReviewWorkflowNode node, Map<String, Object> snapshot) {
        if (node == ReviewWorkflowNode.WAIT_FOR_HUMAN_CONFIRMATION) {
            checkpointService.markWaitingHuman(requireWorkflowInstanceId(request), snapshot);
            return;
        }
        checkpointService.markNodeSucceeded(requireWorkflowInstanceId(request), node, snapshot);
    }

    @Override
    public void onNodeFailure(ReviewWorkflowRequest request, ReviewWorkflowNode node,
                              Map<String, Object> snapshot, Exception exception) {
        checkpointService.markFailed(requireWorkflowInstanceId(request), node, snapshot, exception);
    }

    private String requireWorkflowInstanceId(ReviewWorkflowRequest request) {
        if (request.workflowInstanceId() == null || request.workflowInstanceId().isBlank()) {
            throw new IllegalArgumentException("工作流实例 ID 不能为空");
        }
        return request.workflowInstanceId();
    }
}
