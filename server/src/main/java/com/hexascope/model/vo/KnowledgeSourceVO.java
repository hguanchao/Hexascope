/*
 * 文件说明：后端视图对象，定义知识源（版本历史）出参结构。
 */
package com.hexascope.model.vo;

import java.time.LocalDateTime;

/**
 * 知识源版本出参。
 *
 * @param id 知识源 ID
 * @param fileName 来源文件名
 * @param version 当前版本号
 * @param active 是否生效
 * @param importedCount 导入时的文档数
 * @param documentCount 当前库中该知识源的文档数
 * @param createdAt 首次导入时间
 * @param updatedAt 最近更新时间
 */
public record KnowledgeSourceVO(
        String id,
        String fileName,
        Integer version,
        Boolean active,
        Long importedCount,
        Long documentCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}