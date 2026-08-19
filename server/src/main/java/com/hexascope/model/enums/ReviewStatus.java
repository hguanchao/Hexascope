/*
 * 文件说明：业务枚举定义，集中维护状态、等级、维度或审计动作取值。
 */
package com.hexascope.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审查状态枚举
 *
 * @author Hexascope Team
 */
@Getter
@AllArgsConstructor
public enum ReviewStatus {

    /**
     * AI 工作流已接收任务但尚未产出分数，前端需要轮询展示最新结果。
     */
    REVIEWING("reviewing", "评分中"),

    /**
     * AI 已完成评分，等待人工确认通过、打回或要求修改。
     */
    PENDING("pending", "待评审"),

    /**
     * 人工确认需求质量达标。
     */
    APPROVED("approved", "已通过"),

    /**
     * 人工确认需求不可进入后续流程。
     */
    REJECTED("rejected", "已打回"),

    /**
     * 人工确认需求需要补充信息后再重新审查。
     */
    NEEDS_REVISION("needs_revision", "需修改"),

    /**
     * AI 评分异常结束，允许用户重新触发审查。
     */
    REVIEW_FAILED("review_failed", "评分失败");

    private final String code;
    private final String description;
}
