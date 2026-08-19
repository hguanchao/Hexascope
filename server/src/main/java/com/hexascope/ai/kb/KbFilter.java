/*
 * 文件说明：知识库检索过滤条件，承载标准化后的过滤键值并提供表达式生成能力。
 */
package com.hexascope.ai.kb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库检索过滤条件（不可变）。
 *
 * <p>过滤键固定为 dimension（评分维度）/ source（知识来源）/ severity（严重程度），
 * 值由 {@link MetadataFilterFactory} 统一校验和标准化，此处不再做合法性检查。</p>
 *
 * <p>同一个键下多个值表示 OR 关系，不同键之间为 AND 关系。</p>
 *
 * @param conditions 标准化后的过滤键值
 */
public record KbFilter(Map<String, List<String>> conditions) {

    /**
     * 空过滤条件，表示不过滤。
     */
    public static final KbFilter EMPTY = new KbFilter(Map.of());

    /**
     * 仅召回生效知识来源的内部过滤，所有检索 query 统一附加，与用户过滤 AND 合并。
     */
    public static final KbFilter ACTIVE_SOURCE =
            new KbFilter(Map.of("source_active", List.of("true")));

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    /**
     * 生成 Spring AI 过滤表达式 DSL 字符串。
     *
     * <p>用于向量路 {@code SearchRequest.filterExpression(...)}，语法示例：
     * {@code dimension == '完整性' && source in ['评分细则', '扣分项参考']}。</p>
     */
    public String toFilterExpression() {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : conditions.entrySet()) {
            List<String> escaped = entry.getValue().stream().map(KbFilter::escapeDslValue).toList();
            parts.add(escaped.size() == 1
                    ? entry.getKey() + " == '" + escaped.get(0) + "'"
                    : entry.getKey() + " in ['" + String.join("', '", escaped) + "']");
        }
        return String.join(" && ", parts);
    }

    /**
     * 生成 SQL 条件列表，供关键词路 JdbcTemplate 查询使用。
     *
     * <p>值的匹配走参数绑定，避免人为拼接 SQL。</p>
     */
    public List<SqlCondition> sqlConditions() {
        return conditions.entrySet().stream()
                .map(entry -> new SqlCondition(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 合并额外的过滤条件，返回新实例。
     *
     * <p>调用方需保证额外键与现有键不冲突（重复键以额外值为准）。</p>
     *
     * @param extra 需要追加的过滤键值
     * @return 合并后的过滤条件
     */
    public KbFilter with(Map<String, List<String>> extra) {
        if (extra == null || extra.isEmpty()) {
            return this;
        }
        Map<String, List<String>> merged = new LinkedHashMap<>(conditions);
        merged.putAll(extra);
        return new KbFilter(merged);
    }

    /**
     * DSL 字符串值转义：单引号翻倍，避免表达式被提前截断。
     */
    private static String escapeDslValue(String value) {
        return value.replace("'", "''");
    }

    /**
     * 单条 SQL 过滤条件。
     *
     * @param key    过滤键
     * @param values 取值列表（OR 关系）
     */
    public record SqlCondition(String key, List<String> values) {
    }
}