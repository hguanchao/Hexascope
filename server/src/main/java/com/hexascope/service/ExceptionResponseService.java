/*
 * 文件说明：后端业务服务，集中承载需求评审、配置、审计或外部系统连接逻辑。
 */
package com.hexascope.service;

import org.springframework.stereotype.Service;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 异常响应数据组装 Service
 *
 * <p>全局异常处理器只负责选择 HTTP 状态码和统一响应格式，
 * 具体的参数校验错误提取放在这里，避免控制器异常处理逻辑过长。</p>
 *
 * @author Hexascope Team
 */
@Service
public class ExceptionResponseService {

    /**
     * 提取参数校验错误信息
     *
     * <p>同一个字段可能命中多个校验规则，这里保留第一条错误文案，
     * 让前端能按字段展示稳定的错误信息。</p>
     *
     * @param e 参数校验异常
     * @return 字段与错误信息映射
     */
    public Map<String, String> getValidationErrors(MethodArgumentNotValidException e) {
        return e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(FieldError::getField, FieldError::getDefaultMessage, (a, b) -> a));
    }
}
