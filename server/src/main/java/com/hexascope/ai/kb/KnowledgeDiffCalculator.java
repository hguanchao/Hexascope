/*
 * 文件说明：知识库跨版本行差异计算，纯逻辑便于单元测试。
 */
package com.hexascope.ai.kb;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库跨版本行差异计算。
 *
 * <p>新旧版本按 (来源, Excel 行号) 对齐：只出现在新版本的行记为 added，
 * 只出现在旧版本的行记为 removed，都存在但内容哈希集合不同的行记为 changed。</p>
 *
 * @author Hexascope Team
 */
public final class KnowledgeDiffCalculator {

    private KnowledgeDiffCalculator() {
    }

    /**
     * 计算新旧版本的行差异。
     *
     * @param oldRows 旧版本行快照（来自向量库或归档表）
     * @param newRows 新版本行（分块前按行汇总）
     * @return 差异摘要
     */
    public static DiffResult diff(List<RowSnapshot> oldRows, List<RowSnapshot> newRows) {
        Map<String, Set<String>> oldByKey = groupByRowKey(oldRows);
        Map<String, Set<String>> newByKey = groupByRowKey(newRows);

        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> changed = new LinkedHashSet<>();

        for (String key : newByKey.keySet()) {
            if (!oldByKey.containsKey(key)) {
                added.add(key);
            }
        }
        for (String key : oldByKey.keySet()) {
            if (!newByKey.containsKey(key)) {
                removed.add(key);
            }
        }
        for (String key : newByKey.keySet()) {
            Set<String> oldHashes = oldByKey.get(key);
            if (oldHashes != null && !oldHashes.equals(newByKey.get(key))) {
                changed.add(key);
            }
        }

        return new DiffResult(List.copyOf(added), List.copyOf(removed), List.copyOf(changed));
    }

    /**
     * 按 (来源, 行号) 分组，值为该行所有分块的内容哈希集合。
     */
    private static Map<String, Set<String>> groupByRowKey(List<RowSnapshot> rows) {
        Map<String, Set<String>> grouped = new LinkedHashMap<>();
        for (RowSnapshot row : rows) {
            grouped.computeIfAbsent(row.rowKey(), k -> new LinkedHashSet<>()).add(row.contentHash());
        }
        return grouped;
    }

    /**
     * 行级快照。
     *
     * @param source      来源（评分细则 / 扣分项参考）
     * @param row         原始 Excel 行号
     * @param contentHash 内容哈希，老数据可能缺失
     */
    public record RowSnapshot(String source, int row, String contentHash) {

        /**
         * 跨版本对齐用的行标识。
         */
        public String rowKey() {
            return source + "#" + row;
        }
    }

    /**
     * 差异摘要：行的展示标识列表。
     */
    public record DiffResult(List<String> added, List<String> removed, List<String> changed) {
    }
}