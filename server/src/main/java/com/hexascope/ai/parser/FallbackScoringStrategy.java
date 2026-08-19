/*
 * 文件说明：AI 响应解析组件，负责把模型输出转换为结构化评分结果。
 */
package com.hexascope.ai.parser;

import cn.hutool.core.util.StrUtil;
import com.hexascope.model.enums.ReviewDimension;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 降级评分策略
 *
 * <p>当 LLM 调用失败或响应解析失败时，使用基于字段填空检查的本地规则生成基础评分。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
public class FallbackScoringStrategy {

    private static final int DEFAULT_SCORE = 5;
    /**
     * 需求中出现这些词时，通常说明表达比较泛，需要在明确性维度扣分。
     */
    private static final String[] VAGUE_WORDS = {"优化", "提升", "改进", "加强", "改善", "增强"};

    /**
     * 降级评分
     *
     * @param title       需求标题
     * @param description 需求描述
     * @param priority    优先级
     * @return 解析后的审查结果
     */
    public ParsedReviewResult fallbackScore(String title, String description, String priority) {
        log.warn("使用降级评分策略，需求标题: {}", title);

        // 降级评分只做基础字段检查，不试图替代大模型完整审查，所以分数整体偏保守。
        int completeness = scoreCompleteness(description);
        int clarity = scoreClarity(title, description);
        int feasibility = scoreFeasibility(description);
        int valueAlignment = scoreValueAlignment(priority);
        int testability = scoreTestability(description);
        int format = scoreFormat(title);

        Map<String, Integer> scores = Map.of(
                ReviewDimension.COMPLETENESS.getCode(), completeness,
                ReviewDimension.CLARITY.getCode(), clarity,
                ReviewDimension.FEASIBILITY.getCode(), feasibility,
                ReviewDimension.VALUE_ALIGNMENT.getCode(), valueAlignment,
                ReviewDimension.TESTABILITY.getCode(), testability,
                ReviewDimension.FORMAT.getCode(), format
        );

        return new ParsedReviewResult(
                scores,
                buildFallbackSuggestions(scores),
                "降级评分：LLM 不可用时基于规则的基础评估",
                "建议补充需求描述和验收标准后重新触发 AI 审查"
        );
    }

    /**
     * 构造降级评分时的结构化建议。
     *
     * <p>即使没有大模型结果，也保持和正常评分一致的字段结构，前端和详情接口不需要特殊分支。</p>
     */
    private Map<String, Object> buildFallbackSuggestions(Map<String, Integer> scores) {
        Map<String, Object> suggestions = new LinkedHashMap<>();
        for (ReviewDimension dimension : ReviewDimension.values()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("suggestions", buildDimensionSuggestions(dimension, scores.get(dimension.getCode())));
            detail.put("evidence", List.of("本次为降级评分，仅基于本地规则和字段完整度判断"));
            detail.put("missing_items", List.of("未获得大模型结构化评分依据"));
            detail.put("score_reason", "LLM 不可用或输出不可解析，系统使用本地规则生成保守评分");
            detail.put("confidence", 0.3);
            suggestions.put(dimension.getCode(), detail);
        }
        return suggestions;
    }

    /**
     * 根据维度生成兜底建议。
     *
     * <p>这里给的是保守通用建议，只用于模型不可用或输出不可解析时的应急展示。</p>
     */
    private List<String> buildDimensionSuggestions(ReviewDimension dimension, Integer score) {
        if (score != null && score >= 8) {
            return List.of();
        }
        return switch (dimension) {
            case COMPLETENESS -> List.of("补充需求背景、业务规则、异常场景和验收标准");
            case CLARITY -> List.of("将模糊表述改为明确的输入、处理规则和预期结果");
            case FEASIBILITY -> List.of("补充实现约束、依赖系统、范围边界和风险说明");
            case VALUE_ALIGNMENT -> List.of("补充用户价值、业务目标和可衡量指标");
            case TESTABILITY -> List.of("补充可量化、可验证的验收标准");
            case FORMAT -> List.of("补充规范标题、字段分类、优先级和必要标签");
        };
    }

    /**
     * 完整性主要看需求描述长度。
     *
     * <p>这是降级规则，不代表生产评分标准；正常评分仍由模型结合评分标准判断。</p>
     */
    private int scoreCompleteness(String description) {
        if (StrUtil.isBlank(description)) {
            return 1;
        }
        if (description.length() < 50) {
            return 3;
        }
        if (description.length() < 200) {
            return 5;
        }
        return 7;
    }

    /**
     * 明确性主要看标题是否存在，以及描述中是否包含明显泛化词。
     */
    private int scoreClarity(String title, String description) {
        if (StrUtil.isBlank(title)) {
            return 2;
        }
        if (StrUtil.isNotBlank(description) && containsVagueWords(description)) {
            return 4;
        }
        return 6;
    }

    /**
     * 可行性在降级模式下缺少上下文，只做空描述兜底。
     */
    private int scoreFeasibility(String description) {
        if (StrUtil.isBlank(description)) {
            return DEFAULT_SCORE;
        }
        return 6;
    }

    /**
     * 价值对齐在降级模式下只参考优先级是否填写。
     */
    private int scoreValueAlignment(String priority) {
        if (StrUtil.isBlank(priority)) {
            return DEFAULT_SCORE;
        }
        return 6;
    }

    /**
     * 可测试性主要看是否出现验收、测试或预期结果相关描述。
     */
    private int scoreTestability(String description) {
        if (StrUtil.isBlank(description)) {
            return 2;
        }
        if (StrUtil.containsAny(description, "验收", "测试", "预期")) {
            return 7;
        }
        return 4;
    }

    /**
     * 格式规范在降级模式下只检查标题是否存在和是否带有方括号分类。
     */
    private int scoreFormat(String title) {
        if (StrUtil.isBlank(title)) {
            return 2;
        }
        if (StrUtil.startWith(title, '[') && StrUtil.contains(title, ']')) {
            return 8;
        }
        return 5;
    }

    /**
     * 判断文本中是否包含泛化表达。
     */
    private boolean containsVagueWords(String text) {
        return StrUtil.containsAny(text, VAGUE_WORDS);
    }
}
