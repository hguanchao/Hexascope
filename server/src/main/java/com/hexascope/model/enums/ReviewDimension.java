/*
 * 文件说明：业务枚举定义，集中维护状态、等级、维度或审计动作取值。
 */
package com.hexascope.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审查维度枚举
 *
 * @author Hexascope Team
 */
@Getter
@AllArgsConstructor
public enum ReviewDimension {

    COMPLETENESS("completeness", "完整性", 0.25),
    CLARITY("clarity", "明确性", 0.20),
    FEASIBILITY("feasibility", "可行性", 0.15),
    VALUE_ALIGNMENT("value_alignment", "价值对齐", 0.20),
    TESTABILITY("testability", "可测试性", 0.10),
    FORMAT("format", "格式规范", 0.10);

    private final String code;
    private final String name;
    private final double defaultWeight;
}
