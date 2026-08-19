/*
 * 文件说明：业务枚举定义，集中维护状态、等级、维度或审计动作取值。
 */
package com.hexascope.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 需求质量等级枚举
 *
 * @author Hexascope Team
 */
@Getter
@AllArgsConstructor
public enum ReviewLevel {

    EXCELLENT("优秀", 85, 100),
    GOOD("良好", 70, 84),
    NEEDS_IMPROVEMENT("待改进", 55, 69),
    POOR("不合格", 0, 54);

    private final String description;
    private final int minScore;
    private final int maxScore;

    /**
     * 根据总分判定质量等级
     *
     * @param totalScore 总分
     * @return 质量等级
     */
    public static ReviewLevel fromScore(int totalScore) {
        for (ReviewLevel level : values()) {
            if (totalScore >= level.minScore && totalScore <= level.maxScore) {
                return level;
            }
        }
        return POOR;
    }
}
