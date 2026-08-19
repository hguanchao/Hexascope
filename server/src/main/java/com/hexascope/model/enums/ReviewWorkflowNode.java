/*
 * 文件说明：业务枚举定义，集中维护状态、等级、维度或审计动作取值。
 */
package com.hexascope.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * AI 审查工作流节点枚举
 *
 * @author Hexascope Team
 */
@Getter
@AllArgsConstructor
public enum ReviewWorkflowNode {

    SCORE_REQUIREMENT("scoreRequirement", "需求评分"),

    REPAIR_OUTPUT("repairOutput", "修复输出"),

    VALIDATE_RESULT("validateResult", "校验结果"),

    WAIT_FOR_HUMAN_CONFIRMATION("waitForHumanConfirmation", "等待人工确认");

    private final String code;
    private final String description;

    public static ReviewWorkflowNode fromCode(String code) {
        return Arrays.stream(values())
                .filter(node -> node.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的工作流节点: " + code));
    }
}
