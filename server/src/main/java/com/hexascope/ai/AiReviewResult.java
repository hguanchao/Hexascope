/*
 * 文件说明：AI 评审核心组件，负责组织需求内容、调用模型并返回评分结果。
 */
package com.hexascope.ai;

import com.hexascope.ai.parser.ParsedReviewResult;

import java.io.Serializable;

/**
 * AI 审查结果。
 *
 * @param parsedResult 结构化评分结果
 * @param rawPrompt 原始提示词
 * @param rawAiResponse 原始模型响应
 * @param aiModelUsed 实际使用的模型
 * @param aiLatencyMs AI 调用耗时
 */
public record AiReviewResult(
        ParsedReviewResult parsedResult,
        String rawPrompt,
        String rawAiResponse,
        String aiModelUsed,
        Integer aiLatencyMs
) implements Serializable {
}
