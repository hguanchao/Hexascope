/*
 * 文件说明：接口入参 DTO，定义评估用例创建请求结构。
 */
package com.hexascope.model.dto;

import jakarta.validation.constraints.NotBlank;

import java.io.Serializable;
import java.util.List;

/**
 * 创建（或人工补充）知识库评估用例请求。
 *
 * @param query 检索 query（金标输入）
 * @param expectedDocIds 期望命中的知识片段 ID，可为空（导入时自动生成的自标注用例无需手填）
 * @param dimension 关联评分维度（可选）
 * @param note 备注（可选）
 */
public record CreateEvalCaseRequest(
        @NotBlank(message = "评估用例 query 不能为空")
        String query,

        List<String> expectedDocIds,

        String dimension,

        String note
) implements Serializable {
}