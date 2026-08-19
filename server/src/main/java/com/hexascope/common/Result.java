/*
 * 文件说明：后端公共基础组件，提供统一响应、分页、工具方法或类型处理能力。
 */
package com.hexascope.common;

import java.io.Serializable;

/**
 * 统一 API 响应封装。
 *
 * @param code 响应码，0 表示成功
 * @param message 响应消息
 * @param data 响应数据
 * @param <T> 数据类型
 */
public record Result<T>(
        int code,
        String message,
        T data
) implements Serializable {

    public static <T> Result<T> success(T data) {
        return new Result<>(0, "success", data);
    }

    public static <T> Result<T> error(int code, String message) {
        return new Result<>(code, message, null);
    }
}
