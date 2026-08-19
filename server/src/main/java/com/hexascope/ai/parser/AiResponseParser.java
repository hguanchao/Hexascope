/*
 * 文件说明：AI 响应解析组件，负责把模型输出转换为结构化评分结果。
 */
package com.hexascope.ai.parser;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hexascope.model.enums.ReviewDimension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 输出解析器
 *
 * <p>使用 Hutool JSONUtil 将 LLM 返回的 JSON 文本解析为结构化的审查结果。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
public class AiResponseParser {

    /**
     * 解析 LLM 返回的 JSON 响应
     *
     * @param aiResponse LLM 原始响应文本
     * @return 解析后的审查结果
     * @throws JSONException JSON 解析失败时抛出
     */
    public ParsedReviewResult parse(String aiResponse) throws JSONException {
        String json = extractJson(aiResponse);
        JSONObject root = JSONUtil.parseObj(json);

        // 使用 LinkedHashMap 保持维度输出顺序稳定，便于前端展示和日志排查。
        Map<String, Integer> dimensionScores = new LinkedHashMap<>(6);
        Map<String, Object> aiSuggestions = new LinkedHashMap<>(6);

        JSONObject dimensionsNode = root.getJSONObject("dimensions");
        if (dimensionsNode == null) {
            throw new JSONException("LLM 响应缺少 dimensions 字段");
        }

        for (ReviewDimension dimension : ReviewDimension.values()) {
            JSONObject dimNode = dimensionsNode.getJSONObject(dimension.getCode());
            if (dimNode == null) {
                // 某个维度缺失时给中性分，并补空结构，避免后续总分计算和详情组装空指针。
                dimensionScores.put(dimension.getCode(), 5);
                aiSuggestions.put(dimension.getCode(), emptyDimensionReview());
                continue;
            }

            int score = dimNode.getInt("score", 5);
            score = Math.max(1, Math.min(10, score));
            dimensionScores.put(dimension.getCode(), score);

            aiSuggestions.put(dimension.getCode(), parseDimensionReview(dimNode));
        }

        String summary = root.getStr("summary", StrUtil.EMPTY);
        String improvementSuggestion = root.getStr("improvement_suggestion", StrUtil.EMPTY);

        return new ParsedReviewResult(
                dimensionScores,
                aiSuggestions,
                summary,
                improvementSuggestion
        );
    }

    /**
     * 解析单个维度的结构化反馈。
     *
     * <p>除了建议列表，还会保留证据、缺失项、扣分原因和置信度，方便详情页解释分数来源。</p>
     */
    private Map<String, Object> parseDimensionReview(JSONObject dimNode) {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("suggestions", getStringList(dimNode, "suggestions"));
        review.put("evidence", getStringList(dimNode, "evidence"));
        review.put("missing_items", getStringList(dimNode, "missing_items"));
        review.put("score_reason", dimNode.getStr("score_reason", StrUtil.EMPTY));
        review.put("confidence", getConfidence(dimNode));
        return review;
    }

    /**
     * 生成空维度反馈结构。
     *
     * <p>模型漏掉某个维度时也返回稳定结构，避免前端需要处理多种空值形态。</p>
     */
    private Map<String, Object> emptyDimensionReview() {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("suggestions", List.of());
        review.put("evidence", List.of());
        review.put("missing_items", List.of());
        review.put("score_reason", StrUtil.EMPTY);
        review.put("confidence", 0.0);
        return review;
    }

    /**
     * 从 JSON 数组字段中提取字符串列表。
     */
    private List<String> getStringList(JSONObject node, String field) {
        JSONArray array = node.getJSONArray(field);
        if (CollUtil.isEmpty(array)) {
            return List.of();
        }
        return array.stream()
                .map(Object::toString)
                .filter(StrUtil::isNotBlank)
                .toList();
    }

    /**
     * 解析模型返回的置信度。
     *
     * <p>模型可能返回数字或字符串，这里统一转换并限制在 0 到 1 之间。</p>
     */
    private double getConfidence(JSONObject node) {
        Object value = node.get("confidence");
        double confidence = 0.5;
        if (value instanceof Number number) {
            confidence = number.doubleValue();
        } else if (value instanceof CharSequence text && StrUtil.isNotBlank(text)) {
            try {
                confidence = Double.parseDouble(text.toString());
            } catch (NumberFormatException ignored) {
                confidence = 0.5;
            }
        }
        return Math.max(0, Math.min(1, confidence));
    }

    /**
     * 从 LLM 响应中提取 JSON 文本
     *
     * <p>LLM 可能在 JSON 前后添加 markdown 标记或其他文本，
     * 此方法负责提取纯 JSON 文本。</p>
     *
     * @param response LLM 原始响应
     * @return 纯 JSON 文本
     */
    private String extractJson(String response) {
        if (StrUtil.isBlank(response)) {
            throw new JSONException("LLM 响应为空");
        }

        String trimmed = StrUtil.trim(response);

        // 去除 markdown 代码块标记
        trimmed = StrUtil.removePrefix(trimmed, "```json");
        trimmed = StrUtil.removePrefix(trimmed, "```");
        trimmed = StrUtil.removeSuffix(trimmed, "```");
        trimmed = StrUtil.trim(trimmed);

        // 定位第一个 { 和最后一个 }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return StrUtil.sub(trimmed, start, end + 1);
        }

        return trimmed;
    }

}
