/*
 * 文件说明：接口入参 DTO，定义前端提交的重新审查数据结构。
 */
package com.hexascope.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * 重新审查请求。
 *
 * @param requirementId 需求业务 ID
 * @param reason 重新审查原因，会进入审计日志
 */
public record RetriggerRequest(
        @NotBlank(message = "需求 ID 不能为空")
        String requirementId,

        String reason
) implements Serializable {
}
