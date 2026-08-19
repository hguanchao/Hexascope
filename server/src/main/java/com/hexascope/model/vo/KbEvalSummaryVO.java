/*
 * 文件说明：后端视图对象，定义评估汇总对比出参结构。
 */
package com.hexascope.model.vo;

import java.time.LocalDateTime;

/**
 * 评估汇总对比出参：每个用例两种模式（向量/混合）最近一次运行的指标并排展示。
 *
 * @param caseId 用例 ID
 * @param query 检索 query
 * @param dimension 关联评分维度
 * @param recallVector 纯向量召回率
 * @param precisionVector 纯向量精确率
 * @param mrrVector 纯向量 MRR
 * @param recallHybrid 混合召回率
 * @param precisionHybrid 混合精确率
 * @param mrrHybrid 混合 MRR
 * @param executedAt 最近一次运行时间
 */
public record KbEvalSummaryVO(
        String caseId,
        String query,
        String dimension,
        Double recallVector,
        Double precisionVector,
        Double mrrVector,
        Double recallHybrid,
        Double precisionHybrid,
        Double mrrHybrid,
        LocalDateTime executedAt
) {
}