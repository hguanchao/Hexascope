/*
 * 文件说明：数据库实体模型，描述业务表字段与 Java 对象之间的映射。
 */
package com.hexascope.model.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.hexascope.common.PostgresLocalDateTimeTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 审计日志实体
 *
 * @author Hexascope Team
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "audit_log", autoResultMap = true)
public class AuditLog {

    @TableId(type = IdType.INPUT)
    @Builder.Default
    private UUID id = UUID.randomUUID();

    private String operator;

    private String action;

    private String targetType;

    private String targetId;

    @TableField(typeHandler = com.hexascope.common.JsonbTypeHandler.class)
    private Map<String, Object> detail;

    private String ipAddress;

    @TableField(fill = FieldFill.INSERT, typeHandler = PostgresLocalDateTimeTypeHandler.class)
    private LocalDateTime createdAt;
}
