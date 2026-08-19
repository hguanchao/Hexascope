/*
 * 文件说明：后端公共基础组件，提供统一响应、分页、工具方法或类型处理能力。
 */
package com.hexascope.common;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果封装。
 *
 * @param items 数据列表
 * @param total 总数
 * @param page 当前页
 * @param pageSize 每页大小
 * @param <T> 数据类型
 */
public record PageResult<T>(
        List<T> items,
        Long total,
        Integer page,
        Integer pageSize
) implements Serializable {

    public static <T> PageResult<T> of(List<T> items, Long total, Integer page, Integer pageSize) {
        return new PageResult<>(items, total, page, pageSize);
    }
}
