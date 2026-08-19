/*
 * 文件说明：后端视图对象，定义评估运行结果出参结构。
 */
package com.hexascope.model.vo;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 评估运行结果出参。
 *
 * @param id 运行 ID
 * @param caseId 用例 ID
 * @param config 运行配置（mode/topK/similarityThreshold/hybridEnabled）
 * @param metrics 指标（recall/precision/mrr/latencyMs 等）
 * @param executedAt 运行时间
 */
public record KbEvalRunVO(
        String id,
        String caseId,
        Map<String, Object> config,
        Map<String, Object> metrics,
        LocalDateTime executedAt
) {
}