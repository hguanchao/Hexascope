/*
 * 文件说明：后端业务服务，集中承载需求评审工作流断点创建、状态更新和恢复控制逻辑。
 */
package com.hexascope.service;

import com.hexascope.mapper.ReviewWorkflowCheckpointMapper;
import com.hexascope.model.entity.ReviewRecord;
import com.hexascope.model.entity.ReviewWorkflowCheckpoint;
import com.hexascope.model.enums.ReviewWorkflowNode;
import com.hexascope.model.enums.WorkflowCheckpointStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 审查工作流断点 Service
 *
 * @author Hexascope Team
 */
@Service
@RequiredArgsConstructor
public class WorkflowCheckpointService {

    private static final int MAX_RESUME_COUNT = 3;

    private final ReviewWorkflowCheckpointMapper checkpointMapper;

    public ReviewWorkflowCheckpoint createFor(ReviewRecord record) {
        Map<String, Object> stateSnapshot = new LinkedHashMap<>();
        stateSnapshot.put("reviewId", String.valueOf(record.getId()));
        stateSnapshot.put("requirementId", record.getRequirementId() != null ? record.getRequirementId() : "");

        ReviewWorkflowCheckpoint checkpoint = ReviewWorkflowCheckpoint.builder()
                .reviewId(record.getId())
                .requirementId(record.getRequirementId())
                .workflowInstanceId(UUID.randomUUID().toString())
                .currentNode(ReviewWorkflowNode.SCORE_REQUIREMENT.getCode())
                .status(WorkflowCheckpointStatus.RUNNING.getCode())
                .stateSnapshot(stateSnapshot)
                .build();

        checkpointMapper.insert(checkpoint);
        return checkpoint;
    }

    public void markNodeRunning(String workflowInstanceId, ReviewWorkflowNode currentNode,
                                Map<String, Object> stateSnapshot) {
        updateNodeState(
                workflowInstanceId,
                currentNode,
                WorkflowCheckpointStatus.RUNNING,
                stateSnapshot,
                null,
                false
        );
    }

    public void markNodeSucceeded(String workflowInstanceId, ReviewWorkflowNode currentNode,
                                  Map<String, Object> stateSnapshot) {
        updateNodeState(
                workflowInstanceId,
                currentNode,
                WorkflowCheckpointStatus.RUNNING,
                stateSnapshot,
                null,
                false
        );
    }

    public void markFailed(String workflowInstanceId, ReviewWorkflowNode currentNode,
                           Map<String, Object> stateSnapshot, Exception exception) {
        updateNodeState(
                workflowInstanceId,
                currentNode,
                WorkflowCheckpointStatus.FAILED,
                stateSnapshot,
                errorMessageOf(exception),
                false
        );
    }

    public void markWaitingHuman(String workflowInstanceId, Map<String, Object> stateSnapshot) {
        updateNodeState(
                workflowInstanceId,
                ReviewWorkflowNode.WAIT_FOR_HUMAN_CONFIRMATION,
                WorkflowCheckpointStatus.WAITING_HUMAN,
                stateSnapshot,
                null,
                false
        );
    }

    public void markCompleted(String workflowInstanceId, Map<String, Object> stateSnapshot) {
        updateNodeState(
                workflowInstanceId,
                ReviewWorkflowNode.WAIT_FOR_HUMAN_CONFIRMATION,
                WorkflowCheckpointStatus.COMPLETED,
                stateSnapshot,
                null,
                true
        );
    }

    public ReviewWorkflowCheckpoint findLatestByReviewId(UUID reviewId) {
        ReviewWorkflowCheckpoint checkpoint = checkpointMapper.findLatestByReviewId(reviewId);
        if (checkpoint == null) {
            throw new IllegalArgumentException("未找到工作流断点: " + reviewId);
        }
        return checkpoint;
    }

    public ReviewWorkflowCheckpoint startResume(UUID reviewId) {
        ReviewWorkflowCheckpoint checkpoint = findLatestByReviewId(reviewId);
        if (!WorkflowCheckpointStatus.FAILED.getCode().equals(checkpoint.getStatus())) {
            throw new IllegalArgumentException("当前工作流不需要恢复: " + checkpoint.getStatus());
        }

        int updated = checkpointMapper.tryStartResume(
                checkpoint.getId(),
                WorkflowCheckpointStatus.RUNNING.getCode(),
                WorkflowCheckpointStatus.FAILED.getCode(),
                MAX_RESUME_COUNT
        );
        if (updated == 0) {
            throw new IllegalArgumentException("工作流正在恢复或恢复次数已达上限");
        }

        ReviewWorkflowCheckpoint refreshed =
                checkpointMapper.findByWorkflowInstanceId(checkpoint.getWorkflowInstanceId());
        if (refreshed == null) {
            throw new IllegalStateException("恢复后的工作流断点不存在: " + checkpoint.getWorkflowInstanceId());
        }
        return refreshed;
    }

    private void updateNodeState(String workflowInstanceId, ReviewWorkflowNode currentNode,
                                 WorkflowCheckpointStatus status, Map<String, Object> stateSnapshot,
                                 String errorMessage, boolean completed) {
        int updated = checkpointMapper.updateNodeState(
                workflowInstanceId,
                currentNode.getCode(),
                status.getCode(),
                safeSnapshot(stateSnapshot),
                errorMessage,
                completed
        );
        if (updated == 0) {
            throw new IllegalStateException("工作流断点状态未更新: " + workflowInstanceId);
        }
    }

    private Map<String, Object> safeSnapshot(Map<String, Object> stateSnapshot) {
        if (stateSnapshot == null) {
            return Map.of();
        }

        Map<String, Object> safeSnapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : stateSnapshot.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                safeSnapshot.put(entry.getKey(), entry.getValue());
            }
        }
        return safeSnapshot.isEmpty() ? Map.of() : safeSnapshot;
    }

    private String errorMessageOf(Exception exception) {
        if (exception == null) {
            return null;
        }
        return exception.getClass().getSimpleName() + ": 工作流节点执行失败";
    }
}
