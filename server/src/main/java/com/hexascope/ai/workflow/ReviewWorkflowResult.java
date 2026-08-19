/*
 * 文件说明：AI 评分工作流结果，统一返回可持久化的评分字段。
 */
package com.hexascope.ai.workflow;

import java.io.Serializable;
import java.util.Map;

/**
 * ReviewWorkflow 的可落库输出。
 *
 * <p>工作流内部可能逐步产生中间状态，但对业务服务只暴露最终评分字段，
 * 让保存逻辑保持简单明确。</p>
 *
 * @param totalScore 百分制总分
 * @param level 评分等级
 * @param dimensionScores 六个维度的 1-10 分
 * @param aiSuggestions 各维度改进建议
 * @param improvementSuggestion 总体改进建议
 * @param aiModelUsed 实际使用的模型
 * @param aiLatencyMs AI 调用耗时
 * @param rawPrompt 原始提示词
 * @param rawAiResponse 原始模型响应
 */
public record ReviewWorkflowResult(
        Integer totalScore,
        String level,
        Map<String, Integer> dimensionScores,
        Map<String, Object> aiSuggestions,
        String improvementSuggestion,
        String aiModelUsed,
        Integer aiLatencyMs,
        String rawPrompt,
        String rawAiResponse
) implements Serializable {
}
