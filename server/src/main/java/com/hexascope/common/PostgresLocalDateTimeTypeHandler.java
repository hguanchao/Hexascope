/*
 * 文件说明：后端公共基础组件，提供统一响应、分页、工具方法或类型处理能力。
 */
package com.hexascope.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;

/**
 * PostgreSQL 时间类型处理器。
 *
 * <p>项目实体统一使用 {@link LocalDateTime}，数据库迁移脚本中时间字段使用
 * {@code TIMESTAMP WITH TIME ZONE}。PostgreSQL JDBC 默认不能直接把该类型读取为
 * {@code LocalDateTime}，这里统一按 {@link Timestamp} 读取后再转换。</p>
 *
 * @author Hexascope Team
 */
@MappedTypes(LocalDateTime.class)
public class PostgresLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setTimestamp(i, Timestamp.valueOf(parameter));
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toLocalDateTime(rs.getTimestamp(columnName), rs.wasNull());
    }

    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toLocalDateTime(rs.getTimestamp(columnIndex), rs.wasNull());
    }

    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toLocalDateTime(cs.getTimestamp(columnIndex), cs.wasNull());
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp, boolean wasNull) {
        if (wasNull || timestamp == null) {
            return null;
        }
        return timestamp.toLocalDateTime();
    }
}
