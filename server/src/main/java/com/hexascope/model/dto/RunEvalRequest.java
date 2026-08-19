/*
 * 文件说明：接口入参 DTO，定义评估运行请求结构。
 */
package com.hexascope.model.dto;

import java.io.Serializable;

/**
 * 评估运行请求。
 *
 * @param mode 运行模式：vector（纯向量）/ hybrid（混合）/ all（两种都跑，默认）
 * @param topK 检索候选数，缺省 20
 * @param similarityThreshold 向量相似度阈值，缺省 0.65
 */
public record RunEvalRequest(
        String mode,
        Integer topK,
        Double similarityThreshold
) implements Serializable {
}