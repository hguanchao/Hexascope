/*
 * 文件说明：知识库过滤条件工厂，负责把外部传入的过滤参数校验并标准化为 KbFilter。
 */
package com.hexascope.ai.kb;

import com.hexascope.model.enums.ReviewDimension;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库检索过滤条件工厂。
 *
 * <p>外部请求（如创建需求时的 kbFilters 参数）可能携带任意键值，
 * 这里统一做三件事：白名单过滤键、标准化维度名、去空去重。</p>
 *
 * @author Hexascope Team
 */
public final class MetadataFilterFactory {

    /**
     * 允许的过滤键。
     */
    public static final String KEY_DIMENSION = "dimension";
    public static final String KEY_SOURCE = "source";
    public static final String KEY_SEVERITY = "severity";

    /**
     * 白名单之外的键直接忽略，避免未来新增业务键时影响旧数据。
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(KEY_DIMENSION, KEY_SOURCE, KEY_SEVERITY);

    private MetadataFilterFactory() {
    }

    /**
     * 把外部传入的过滤参数标准化为 {@link KbFilter}。
     *
     * <p>维度值支持中文名（完整性）、code（completeness）或枚举名（COMPLETENESS），
     * 统一转为入库时使用的维度中文名；非法维度值抛异常（由全局异常处理转 400），
     * 未知过滤键和空值直接忽略。</p>
     *
     * @param raw 外部传入的过滤键值，可为 null
     * @return 标准化后的过滤条件，空输入返回 {@link KbFilter#EMPTY}
     */
    public static KbFilter create(Map<String, List<String>> raw) {
        if (raw == null || raw.isEmpty()) {
            return KbFilter.EMPTY;
        }
        Map<String, List<String>> conditions = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : raw.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (!ALLOWED_KEYS.contains(key) || entry.getValue() == null) {
                continue;
            }
            Set<String> values = new LinkedHashSet<>();
            for (String value : entry.getValue()) {
                if (value == null) {
                    continue;
                }
                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                values.add(KEY_DIMENSION.equals(key) ? normalizeDimension(trimmed) : trimmed);
            }
            if (!values.isEmpty()) {
                conditions.put(key, List.copyOf(values));
            }
        }
        return conditions.isEmpty() ? KbFilter.EMPTY : new KbFilter(conditions);
    }

    /**
     * 把维度过滤值归一化为入库使用的维度中文名。
     *
     * @param value 维度中文名、code 或枚举名
     * @return 维度中文名
     * @throws IllegalArgumentException 不是已知评分维度时抛出
     */
    private static String normalizeDimension(String value) {
        for (ReviewDimension dimension : ReviewDimension.values()) {
            if (dimension.getName().equals(value)
                    || dimension.getCode().equals(value)
                    || dimension.name().equalsIgnoreCase(value)) {
                return dimension.getName();
            }
        }
        throw new IllegalArgumentException("未知的评分维度过滤值: " + value);
    }
}