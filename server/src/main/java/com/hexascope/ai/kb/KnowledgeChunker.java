/*
 * 文件说明：知识库分块器，把过长的行级知识文档切成可检索的短块。
 */
package com.hexascope.ai.kb;

import cn.hutool.crypto.digest.DigestUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库分块器。
 *
 * <p>评分标准以 Excel 行为粒度入库，但个别行（例如完整的长描述单元格）可能
 * 超过 embedding 的合理长度。这里把超限行按顺序切块：切分边界优先落在换行或
 * 句末标点，块间保留重叠，并给每块追加块序号与内容哈希（早于文档 ID 生成，
 * 用于跨版本的内容比对）。</p>
 *
 * @author Hexascope Team
 */
@Component
@RequiredArgsConstructor
public class KnowledgeChunker {

    /**
     * metadata 键：块序号（0 起）、总块数、内容哈希。
     */
    public static final String KEY_CHUNK_INDEX = "chunk_index";
    public static final String KEY_TOTAL_CHUNKS = "total_chunks";
    public static final String KEY_CONTENT_HASH = "content_hash";

    /**
     * 句末或段落边界字符，切分时优先在这些位置收尾。
     */
    private static final String BOUNDARY_CHARS = "。！？；.!?\n";

    private final KbChunkProperties properties;

    /**
     * 将一行知识文档分块。
     *
     * <p>未超限的行原样返回（仅补充块元数据，total_chunks=1）；
     * 超限行按顺序切块，继承行级 metadata（source/dimension/row 等）。</p>
     *
     * @param rowDocument 行级文档
     * @return 分块后的文档列表，行内容为空时返回空列表
     */
    public List<Document> chunk(Document rowDocument) {
        if (rowDocument == null) {
            return List.of();
        }
        String text = rowDocument.getText() == null ? "" : rowDocument.getText().trim();
        if (text.isEmpty()) {
            return List.of();
        }

        Map<String, Object> baseMetadata = rowDocument.getMetadata() != null
                ? rowDocument.getMetadata() : Map.of();

        List<String> chunks = splitWithOverlap(text,
                Math.max(1, properties.getMaxCharPerChunk()),
                Math.max(0, properties.getOverlapChars()),
                Math.max(0, properties.getMinChunkChars()));

        // 尾块过短时合并到前一块，避免丢内容；单块场景不存在短块（首块长度受最小块约束保护）。
        if (chunks.size() > 1) {
            String tail = chunks.get(chunks.size() - 1);
            if (tail.length() < Math.max(0, properties.getMinChunkChars())) {
                String merged = chunks.get(chunks.size() - 2) + tail;
                chunks.set(chunks.size() - 2, merged);
                chunks.remove(chunks.size() - 1);
            }
        }

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Map<String, Object> metadata = new HashMap<>(baseMetadata);
            metadata.put(KEY_CHUNK_INDEX, i);
            metadata.put(KEY_TOTAL_CHUNKS, chunks.size());
            metadata.put(KEY_CONTENT_HASH, DigestUtil.sha256Hex(chunks.get(i)));
            documents.add(new Document(chunks.get(i), metadata));
        }
        return documents;
    }

    /**
     * 按顺序切块：每块最长 maxChars，块间保留 overlapChars 重叠，
     * 结束位置优先收敛到段落/句末标点（保证块长不小于 minChunkChars）。
     */
    private List<String> splitWithOverlap(String text, int maxChars, int overlapChars, int minChunkChars) {
        List<String> chunks = new ArrayList<>();
        int length = text.length();
        int start = 0;
        while (start < length) {
            int candidateEnd = Math.min(start + maxChars, length);
            int end = candidateEnd < length
                    ? adjustToBoundary(text, start, candidateEnd, minChunkChars)
                    : candidateEnd;

            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= length) {
                break;
            }
            int nextStart = end - overlapChars;
            if (nextStart <= start) {
                nextStart = start + 1; // 防止 overlap 把起点吞掉导致死循环
            }
            start = nextStart;
        }
        return chunks;
    }

    /**
     * 在候选结束位置附近往回找最近的边界字符。
     *
     * <p>边界位置必须满足块长 >= minChunkChars，否则仍按候选位置硬切。</p>
     */
    private int adjustToBoundary(String text, int start, int candidateEnd, int minChunkChars) {
        int earliestAllowed = Math.min(candidateEnd, start + minChunkChars);
        for (int i = candidateEnd; i > earliestAllowed; i--) {
            char c = text.charAt(i - 1);
            if (BOUNDARY_CHARS.indexOf(c) >= 0) {
                return i;
            }
        }
        return candidateEnd;
    }
}