/*
 * 文件说明：接口入参 DTO，定义前端提交的审查状态更新数据结构。
 */
package com.hexascope.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;

/**
 * 评审状态更新请求。
 *
 * @param status 目标审查状态
 */
public record ReviewStatusUpdateRequest(
        @NotBlank(message = "状态不能为空")
        String status
) implements Serializable {
}
