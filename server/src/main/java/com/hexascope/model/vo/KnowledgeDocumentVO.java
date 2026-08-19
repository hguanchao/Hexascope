/*
 * 文件说明：接口出参 VO，定义页面展示和接口响应需要的数据结构。
 */
package com.hexascope.model.vo;

import java.io.Serializable;

/**
 * 知识库文档展示对象。
 */
public record KnowledgeDocumentVO(
        String id,
        String source,
        String dimension,
        String level,
        String severity,
        Integer row,
        String content
) implements Serializable {
}
