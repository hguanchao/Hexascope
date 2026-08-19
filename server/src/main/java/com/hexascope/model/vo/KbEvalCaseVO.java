/*
 * 文件说明：后端视图对象，定义评估用例出参结构。
 */
package com.hexascope.model.vo;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 评估用例出参。
 *
 * @param id 用例 ID
 * @param query 检索 query
 * @param expectedDocIds 期望命中的知识片段 ID
 * @param dimension 关联评分维度
 * @param source 自动生成的来源（评分细则/扣分项参考），人工用例为空
 * @param rowNum 自动生成的原始 Excel 行号，人工用例为空
 * @param note 备注
 * @param expectedCount 期望命中数
 * @param createdAt 创建时间
 * @param updatedAt 最近更新时间
 */
public record KbEvalCaseVO(
        String id,
        String query,
        List<String> expectedDocIds,
        String dimension,
        String source,
        Integer rowNum,
        String note,
        Integer expectedCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}