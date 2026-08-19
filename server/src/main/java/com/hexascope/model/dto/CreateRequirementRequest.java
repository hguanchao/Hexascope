/*
 * 文件说明：接口入参 DTO，定义前端提交的需求创建数据结构。
 */
package com.hexascope.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 创建需求请求。
 *
 * @param requirementId 需求业务 ID
 * @param title 需求标题
 * @param description 需求描述
 * @param creator 创建人
 * @param priority 优先级
 * @param workspaceId 空间 ID
 * @param teamId 团队 ID
 * @param kbFilters 知识库检索过滤条件（可选）：dimension/source/severity，缺省为不过滤
 */
public record CreateRequirementRequest(
        @NotBlank(message = "需求 ID 不能为空")
        String requirementId,

        @NotBlank(message = "需求标题不能为空")
        String title,

        @NotBlank(message = "需求描述不能为空")
        String description,

        @NotBlank(message = "创建人不能为空")
        String creator,

        @NotBlank(message = "优先级不能为空")
        String priority,

        @NotBlank(message = "空间 ID 不能为空")
        String workspaceId,

        @NotBlank(message = "团队 ID 不能为空")
        String teamId,

        Map<String, List<String>> kbFilters
) implements Serializable {
}
