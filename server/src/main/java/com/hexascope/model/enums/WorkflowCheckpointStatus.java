/*
 * 文件说明：业务枚举定义，集中维护状态、等级、维度或审计动作取值。
 */
package com.hexascope.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 工作流断点状态枚举
 *
 * @author Hexascope Team
 */
@Getter
@AllArgsConstructor
public enum WorkflowCheckpointStatus {

    RUNNING("running", "运行中"),

    FAILED("failed", "失败"),

    WAITING_HUMAN("waiting_human", "等待人工处理"),

    COMPLETED("completed", "已完成");

    private final String code;
    private final String description;
}
