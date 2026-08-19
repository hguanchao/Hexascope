/*
 * 文件说明：知识库分块配置，控制单块长度、重叠与最小块约束。
 */
package com.hexascope.ai.kb;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 知识库分块配置。
 *
 * <p>Excel 行文本超过单块上限时按标点/换行边界切块，块间保留重叠减少语义割裂；
 * 过短的碎块直接丢弃，避免产生无检索价值的噪声块。</p>
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hexascope.kb.chunk")
public class KbChunkProperties {

    /**
     * 单块最大字符数，超过该值的行文本会被切分。
     */
    private int maxCharPerChunk = 1500;

    /**
     * 相邻块之间的重叠字符数。
     */
    private int overlapChars = 200;

    /**
     * 小于该长度的块直接丢弃。
     */
    private int minChunkChars = 100;
}