/*
 * 文件说明：AI 响应解析组件，负责把模型输出转换为结构化评分结果。
 */
package com.hexascope.ai.parser;

import java.io.Serializable;
import java.util.Map;

/**
 * 解析后的审查结果。
 *
 * @param dimensionScores 维度评分
 * @param aiSuggestions AI 建议
 * @param summary 总体评价
 * @param improvementSuggestion 改进建议
 */
public record ParsedReviewResult(
        Map<String, Integer> dimensionScores,
        Map<String, Object> aiSuggestions,
        String summary,
        String improvementSuggestion
) implements Serializable {
}
