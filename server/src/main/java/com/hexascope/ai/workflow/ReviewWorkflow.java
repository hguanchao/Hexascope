/*
 * 文件说明：基于 Spring AI Alibaba Graph 的需求评分工作流。
 */
package com.hexascope.ai.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.hexascope.ai.AiReviewEngine;
import com.hexascope.ai.AiReviewResult;
import com.hexascope.ai.parser.ParsedReviewResult;
import com.hexascope.ai.parser.ReviewOutputRepairer;
import com.hexascope.model.enums.ReviewDimension;
import com.hexascope.model.enums.ReviewLevel;
import com.hexascope.model.enums.ReviewWorkflowNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 需求 AI 评分工作流。
 *
 * <p>该类把“调用模型”和“校验结果”从 ReviewService 中拆出来，用
 * Spring AI Alibaba Graph 显式建模。当前流程包含评分、修复、校验和
 * 人工确认等待四个节点，后续可以继续增加持久化断点等节点。</p>
 */
@Slf4j
@Component
public class ReviewWorkflow {

    /**
     * 节点：调用现有 AI 评分引擎，生成原始评分结果。
     */
    private static final String NODE_SCORE_REQUIREMENT = "scoreRequirement";

    /**
     * 节点：修复 AI 输出，确保维度和分值稳定。
     */
    private static final String NODE_REPAIR_OUTPUT = "repairOutput";

    /**
     * 节点：校验评分结果是否满足业务可落库要求。
     */
    private static final String NODE_VALIDATE_RESULT = "validateResult";

    /**
     * 节点：进入人工确认等待态。
     */
    private static final String NODE_WAIT_FOR_HUMAN_CONFIRMATION = "waitForHumanConfirmation";

    /**
     * Graph 状态键。所有节点通过这些键读写状态，避免在节点之间传递临时对象。
     */
    private static final String REQUIREMENT_ID = "requirementId";
    private static final String TITLE = "title";
    private static final String DESCRIPTION = "description";
    private static final String PRIORITY = "priority";
    private static final String CREATOR = "creator";
    private static final String REVIEW_ID = "reviewId";
    private static final String WORKFLOW_INSTANCE_ID = "workflowInstanceId";
    private static final String CURRENT_REQUEST = "currentRequest";
    private static final String CURRENT_NODE = "currentNode";
    private static final String PARSED_RESULT = "parsedResult";
    private static final String TOTAL_SCORE = "totalScore";
    private static final String LEVEL = "level";
    private static final String DIMENSION_SCORES = "dimensionScores";
    private static final String AI_SUGGESTIONS = "aiSuggestions";
    private static final String IMPROVEMENT_SUGGESTION = "improvementSuggestion";
    private static final String AI_MODEL_USED = "aiModelUsed";
    private static final String AI_LATENCY_MS = "aiLatencyMs";
    private static final String RAW_PROMPT = "rawPrompt";
    private static final String RAW_AI_RESPONSE = "rawAiResponse";

    private final AiReviewEngine aiReviewEngine;
    private final ReviewOutputRepairer repairer;
    private final List<ReviewWorkflowProgressListener> progressListeners;

    /**
     * 编译后的工作流图。构造时编译一次，后续每次评分复用同一个图定义。
     */
    private final CompiledGraph graph;

    public ReviewWorkflow(AiReviewEngine aiReviewEngine, ReviewOutputRepairer repairer,
                          List<ReviewWorkflowProgressListener> progressListeners) {
        this.aiReviewEngine = aiReviewEngine;
        this.repairer = repairer;
        this.progressListeners = progressListeners != null ? progressListeners : List.of();
        this.graph = buildGraph();
    }

    /**
     * 执行一次完整评分工作流。
     *
     * <p>调用方只关心最终可落库结果；节点内部的中间状态由 Graph 管理。</p>
     */
    public ReviewWorkflowResult run(ReviewWorkflowRequest request) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(CURRENT_REQUEST, request);
        input.put(REVIEW_ID, valueOrEmpty(request.reviewId()));
        input.put(WORKFLOW_INSTANCE_ID, valueOrEmpty(request.workflowInstanceId()));
        input.put(REQUIREMENT_ID, valueOrEmpty(request.requirementId()));
        input.put(TITLE, valueOrEmpty(request.title()));
        input.put(DESCRIPTION, valueOrEmpty(request.description()));
        input.put(PRIORITY, valueOrEmpty(request.priority()));
        input.put(CREATOR, valueOrEmpty(request.creator()));

        OverAllState state = graph.invoke(input)
                .orElseThrow(() -> new IllegalStateException("AI 评分工作流未返回结果"));

        return new ReviewWorkflowResult(
                state.value(TOTAL_SCORE, Integer.class).orElseThrow(),
                state.value(LEVEL, String.class).orElseThrow(),
                state.<Map<String, Integer>>value(DIMENSION_SCORES).orElseThrow(),
                state.<Map<String, Object>>value(AI_SUGGESTIONS).orElse(Map.of()),
                state.value(IMPROVEMENT_SUGGESTION, ""),
                state.value(AI_MODEL_USED, ""),
                state.value(AI_LATENCY_MS, Integer.class).orElse(null),
                state.value(RAW_PROMPT, ""),
                state.value(RAW_AI_RESPONSE, "")
        );
    }

    /**
     * 从断点恢复工作流。
     *
     * <p>第一版恢复策略采用完整重跑，保留参数签名供后续断点精确恢复使用。</p>
     */
    public ReviewWorkflowResult resume(ReviewWorkflowRequest request, String failedNode,
                                       Map<String, Object> stateSnapshot) {
        return run(request);
    }

    /**
     * 构建最小评分工作流。
     *
     * <p>当前保留四个节点：评分、修复、校验和人工确认等待。后续如果要加
     * 持久化断点，可以在这里继续扩展节点和条件边。</p>
     */
    private CompiledGraph buildGraph() {
        try {
            return new StateGraph()
                    .addNode(NODE_SCORE_REQUIREMENT, AsyncNodeAction.node_async(this::scoreRequirement))
                    .addNode(NODE_REPAIR_OUTPUT, AsyncNodeAction.node_async(this::repairOutput))
                    .addNode(NODE_VALIDATE_RESULT, AsyncNodeAction.node_async(this::validateResult))
                    .addNode(NODE_WAIT_FOR_HUMAN_CONFIRMATION, AsyncNodeAction.node_async(this::waitForHumanConfirmation))
                    .addEdge(StateGraph.START, NODE_SCORE_REQUIREMENT)
                    .addEdge(NODE_SCORE_REQUIREMENT, NODE_REPAIR_OUTPUT)
                    .addEdge(NODE_REPAIR_OUTPUT, NODE_VALIDATE_RESULT)
                    .addEdge(NODE_VALIDATE_RESULT, NODE_WAIT_FOR_HUMAN_CONFIRMATION)
                    .addEdge(NODE_WAIT_FOR_HUMAN_CONFIRMATION, StateGraph.END)
                    .compile();
        } catch (GraphStateException e) {
            throw new IllegalStateException("初始化 AI 评分工作流失败", e);
        }
    }

    /**
     * 评分节点。
     *
     * <p>这里复用已有的 {@link AiReviewEngine}，避免在引入 Graph 时重写提示词、
     * 检索和解析逻辑。节点输出会合并到 Graph 状态，供后续校验节点读取。</p>
     */
    private Map<String, Object> scoreRequirement(OverAllState state) {
        ReviewWorkflowRequest request = requestFrom(state);
        ReviewWorkflowNode node = ReviewWorkflowNode.SCORE_REQUIREMENT;
        String requirementId = state.value(REQUIREMENT_ID, "");
        String title = state.value(TITLE, "");
        String description = state.value(DESCRIPTION, "");
        String priority = state.value(PRIORITY, "");
        String creator = state.value(CREATOR, "");

        try {
            notifyStart(request, node, state);
            log.info("开始执行 AI 评分工作流: requirementId={}, title={}", requirementId, title);
            AiReviewResult aiResult = aiReviewEngine.review(title, description, priority, creator,
                    request.kbFilters(), request.reviewId());

            Map<String, Object> output = new LinkedHashMap<>();
            output.put(CURRENT_NODE, node.getCode());
            output.put(PARSED_RESULT, aiResult.parsedResult());
            output.put(AI_MODEL_USED, aiResult.aiModelUsed());
            output.put(AI_LATENCY_MS, aiResult.aiLatencyMs());
            output.put(RAW_PROMPT, aiResult.rawPrompt());
            output.put(RAW_AI_RESPONSE, aiResult.rawAiResponse());
            notifySuccess(request, node, state, output);
            return output;
        } catch (RuntimeException e) {
            notifyFailure(request, node, state, e);
            throw e;
        }
    }

    /**
     * 修复输出节点。
     */
    private Map<String, Object> repairOutput(OverAllState state) {
        ReviewWorkflowRequest request = requestFrom(state);
        ReviewWorkflowNode node = ReviewWorkflowNode.REPAIR_OUTPUT;
        try {
            notifyStart(request, node, state);
            ParsedReviewResult parsed = state.value(PARSED_RESULT, ParsedReviewResult.class)
                    .orElse(null);
            ParsedReviewResult repaired = repairer.repair(
                    parsed,
                    state.value(TITLE, ""),
                    state.value(DESCRIPTION, ""),
                    state.value(PRIORITY, "")
            );

            int totalScore = calculateTotalScore(repaired.dimensionScores());
            ReviewLevel level = ReviewLevel.fromScore(totalScore);

            Map<String, Object> output = new LinkedHashMap<>();
            output.put(CURRENT_NODE, node.getCode());
            output.put(TOTAL_SCORE, totalScore);
            output.put(LEVEL, level.name());
            output.put(DIMENSION_SCORES, repaired.dimensionScores());
            output.put(AI_SUGGESTIONS, repaired.aiSuggestions() != null ? repaired.aiSuggestions() : Map.of());
            output.put(IMPROVEMENT_SUGGESTION, repaired.improvementSuggestion());
            notifySuccess(request, node, state, output);
            return output;
        } catch (RuntimeException e) {
            notifyFailure(request, node, state, e);
            throw e;
        }
    }

    /**
     * 校验节点。
     *
     * <p>AI 输出必须先通过这里的硬性校验，再由调用方写入数据库。这样后续新增
     * 自动修复节点时，可以把不合格结果路由到修复分支，而不是直接污染业务表。</p>
     */
    private Map<String, Object> validateResult(OverAllState state) {
        ReviewWorkflowRequest request = requestFrom(state);
        ReviewWorkflowNode node = ReviewWorkflowNode.VALIDATE_RESULT;
        try {
            notifyStart(request, node, state);
            Integer totalScore = state.value(TOTAL_SCORE, Integer.class)
                    .orElseThrow(() -> new IllegalStateException("AI 评分结果缺少总分"));
            Map<String, Integer> dimensionScores = state.<Map<String, Integer>>value(DIMENSION_SCORES)
                    .orElseThrow(() -> new IllegalStateException("AI 评分结果缺少维度分"));

            if (totalScore < 0 || totalScore > 100) {
                throw new IllegalStateException("AI 评分总分超出范围: " + totalScore);
            }
            for (ReviewDimension dimension : ReviewDimension.values()) {
                Integer score = dimensionScores.get(dimension.getCode());
                if (score == null || score < 1 || score > 10) {
                    throw new IllegalStateException("AI 维度评分无效: " + dimension.getCode());
                }
            }
            Map<String, Object> output = Map.of(CURRENT_NODE, node.getCode());
            notifySuccess(request, node, state, output);
            return output;
        } catch (RuntimeException e) {
            notifyFailure(request, node, state, e);
            throw e;
        }
    }

    /**
     * 人工确认等待节点。
     */
    private Map<String, Object> waitForHumanConfirmation(OverAllState state) {
        ReviewWorkflowRequest request = requestFrom(state);
        ReviewWorkflowNode node = ReviewWorkflowNode.WAIT_FOR_HUMAN_CONFIRMATION;
        try {
            notifyStart(request, node, state);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put(CURRENT_NODE, node.getCode());
            notifySuccess(request, node, state, output);
            return output;
        } catch (RuntimeException e) {
            notifyFailure(request, node, state, e);
            throw e;
        }
    }

    /**
     * 将六个维度的 1-10 分按业务权重换算为百分制总分。
     */
    private int calculateTotalScore(Map<String, Integer> dimensionScores) {
        double total = 0;
        for (ReviewDimension dimension : ReviewDimension.values()) {
            Integer score = dimensionScores.get(dimension.getCode());
            if (score != null) {
                total += score * dimension.getDefaultWeight();
            }
        }
        return (int) Math.round(total / 10 * 100);
    }

    /**
     * 旧数据可能没有描述或优先级，进入 Graph 前统一兜底为空字符串。
     */
    private static String valueOrEmpty(String value) {
        return value != null ? value : "";
    }

    private ReviewWorkflowRequest requestFrom(OverAllState state) {
        return state.value(CURRENT_REQUEST, ReviewWorkflowRequest.class)
                .orElseGet(() -> new ReviewWorkflowRequest(
                        state.value(REVIEW_ID, ""),
                        state.value(WORKFLOW_INSTANCE_ID, ""),
                        state.value(REQUIREMENT_ID, ""),
                        state.value(TITLE, ""),
                        state.value(DESCRIPTION, ""),
                        state.value(PRIORITY, ""),
                        state.value(CREATOR, ""),
                        null
                ));
    }

    private void notifyStart(ReviewWorkflowRequest request, ReviewWorkflowNode node, OverAllState state) {
        Map<String, Object> snapshot = safeSnapshot(request, node, state, Map.of());
        for (ReviewWorkflowProgressListener listener : progressListeners) {
            listener.onNodeStart(request, node, snapshot);
        }
    }

    private void notifySuccess(ReviewWorkflowRequest request, ReviewWorkflowNode node, OverAllState state,
                               Map<String, Object> output) {
        Map<String, Object> snapshot = safeSnapshot(request, node, state, output);
        for (ReviewWorkflowProgressListener listener : progressListeners) {
            listener.onNodeSuccess(request, node, snapshot);
        }
    }

    private void notifyFailure(ReviewWorkflowRequest request, ReviewWorkflowNode node, OverAllState state,
                               Exception exception) {
        Map<String, Object> snapshot = safeSnapshot(request, node, state, Map.of());
        for (ReviewWorkflowProgressListener listener : progressListeners) {
            try {
                listener.onNodeFailure(request, node, snapshot, exception);
            } catch (RuntimeException callbackFailure) {
                exception.addSuppressed(callbackFailure);
                log.warn("AI 评分工作流失败回调异常: node={}", node.getCode(), callbackFailure);
            }
        }
    }

    private Map<String, Object> safeSnapshot(ReviewWorkflowRequest request, ReviewWorkflowNode node,
                                             OverAllState state, Map<String, Object> output) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put(REVIEW_ID, valueOrEmpty(request.reviewId()));
        snapshot.put(WORKFLOW_INSTANCE_ID, valueOrEmpty(request.workflowInstanceId()));
        snapshot.put(REQUIREMENT_ID, valueOrEmpty(request.requirementId()));
        snapshot.put(CURRENT_NODE, node.getCode());
        putIfPresent(snapshot, TOTAL_SCORE, output.get(TOTAL_SCORE));
        putIfPresent(snapshot, TOTAL_SCORE, state.value(TOTAL_SCORE, Integer.class).orElse(null));
        putIfPresent(snapshot, LEVEL, output.get(LEVEL));
        putIfPresent(snapshot, LEVEL, state.value(LEVEL, String.class).orElse(null));
        putIfPresent(snapshot, DIMENSION_SCORES, output.get(DIMENSION_SCORES));
        putIfPresent(snapshot, DIMENSION_SCORES, state.<Map<String, Integer>>value(DIMENSION_SCORES).orElse(null));
        return snapshot;
    }

    private void putIfPresent(Map<String, Object> snapshot, String key, Object value) {
        if (value != null && !snapshot.containsKey(key)) {
            snapshot.put(key, value);
        }
    }
}
