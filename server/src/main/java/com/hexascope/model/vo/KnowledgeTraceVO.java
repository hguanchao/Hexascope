/*
 * 文件说明：检索追踪 VO，定义检索打点出参结构。
 */
package com.hexascope.model.vo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 检索追踪记录出参。
 *
 * @param id 记录 ID
 * @param reviewId 审查记录 ID（可为空，如评估或调试场景）
 * @param queries 本次检索生成的所有 query
 * @param candidates 进入精排前的候选（id + score，封顶 50 条）
 * @param selected 精排后进入 Prompt 的文档 ID
 * @param retrievalMs 检索阶段耗时
 * @param rerankMs 精排阶段耗时
 * @param totalMs 检索 + 精排总耗时
 * @param hybridEnabled 检索时是否启用混合检索
 * @param createdAt 打点时间
 */
public record KnowledgeTraceVO(
        String id,
        String reviewId,
        List<String> queries,
        List<Map<String, Object>> candidates,
        List<String> selected,
        Integer retrievalMs,
        Integer rerankMs,
        Integer totalMs,
        Boolean hybridEnabled,
        LocalDateTime createdAt
) {
}