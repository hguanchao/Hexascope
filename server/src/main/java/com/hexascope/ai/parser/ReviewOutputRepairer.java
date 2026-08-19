/*
 * 文件说明：AI 审查结果修复组件，负责把解析结果规整为稳定结构。
 */
package com.hexascope.ai.parser;

import cn.hutool.core.util.StrUtil;
import com.hexascope.model.enums.ReviewDimension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

/**
 * 审查输出修复组件。
 *
 * <p>对模型解析后的评分和建议做确定性修复，确保后续业务拿到稳定字段。</p>
 *
 * @author Hexascope Team
 */
@Component
@RequiredArgsConstructor
public class ReviewOutputRepairer {

    private static final int DEFAULT_SCORE = 5;

    private final FallbackScoringStrategy fallbackScoringStrategy;

    /**
     * 修复解析后的审查结果。
     *
     * @param parsed      解析结果
     * @param title       需求标题
     * @param description 需求描述
     * @param priority    优先级
     * @return 修复后的审查结果
     */
    public ParsedReviewResult repair(ParsedReviewResult parsed, String title, String description, String priority) {
        if (parsed == null || parsed.dimensionScores() == null) {
            return repair(fallbackScoringStrategy.fallbackScore(title, description, priority), title, description, priority);
        }

        Map<String, Integer> repairedScores = new LinkedHashMap<>();
        Map<String, Object> repairedSuggestions = new LinkedHashMap<>();
        Map<String, Object> sourceSuggestions = parsed.aiSuggestions();

        for (ReviewDimension dimension : ReviewDimension.values()) {
            String code = dimension.getCode();
            repairedScores.put(code, clampScore(parsed.dimensionScores().get(code)));
            Object review = sourceSuggestions == null ? null : sourceSuggestions.get(code);
            repairedSuggestions.put(code, repairDimensionReview(review));
        }

        return new ParsedReviewResult(
                repairedScores,
                repairedSuggestions,
                toStringValue(parsed.summary()),
                toStringValue(parsed.improvementSuggestion())
        );
    }

    private int clampScore(Integer score) {
        int value = score == null ? DEFAULT_SCORE : score;
        return Math.max(1, Math.min(10, value));
    }

    private Map<String, Object> repairDimensionReview(Object review) {
        if (!(review instanceof Map<?, ?> source)) {
            return emptyDimensionReview();
        }

        Map<String, Object> repaired = new LinkedHashMap<>();
        repaired.put("suggestions", toStringList(source.get("suggestions")));
        repaired.put("evidence", toStringList(source.get("evidence")));
        repaired.put("missing_items", toStringList(firstNonNull(source.get("missing_items"), source.get("missingItems"))));
        repaired.put("score_reason", toStringValue(firstNonNull(source.get("score_reason"), source.get("scoreReason"))));
        repaired.put("confidence", clampConfidence(source.get("confidence")));
        return repaired;
    }

    private Map<String, Object> emptyDimensionReview() {
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("suggestions", List.of());
        review.put("evidence", List.of());
        review.put("missing_items", List.of());
        review.put("score_reason", StrUtil.EMPTY);
        review.put("confidence", 0.0);
        return review;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private List<String> toStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof CharSequence text) {
            String stringValue = toStringValue(text);
            return StrUtil.isBlank(stringValue) ? List.of() : List.of(stringValue);
        }
        if (value instanceof Iterable<?> iterable) {
            return StreamSupport.stream(iterable.spliterator(), false)
                    .map(this::toStringValue)
                    .filter(StrUtil::isNotBlank)
                    .toList();
        }
        String stringValue = toStringValue(value);
        return StrUtil.isBlank(stringValue) ? List.of() : List.of(stringValue);
    }

    private String toStringValue(Object value) {
        if (value == null) {
            return StrUtil.EMPTY;
        }
        return value.toString();
    }

    private double clampConfidence(Object value) {
        double confidence = 0.0;
        if (value instanceof Number number) {
            confidence = number.doubleValue();
        } else if (value instanceof CharSequence text && StrUtil.isNotBlank(text)) {
            try {
                confidence = Double.parseDouble(text.toString());
            } catch (NumberFormatException ignored) {
                confidence = 0.0;
            }
        }
        return Math.max(0.0, Math.min(1.0, confidence));
    }
}
