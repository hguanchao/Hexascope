/*
 * 文件说明：后端公共基础组件，提供统一响应、分页、工具方法或类型处理能力。
 */
package com.hexascope.common;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * PostgreSQL UUID 类型处理器。
 *
 * <p>实体主键使用 {@link UUID}，数据库字段使用 {@code uuid} 类型。
 * PostgreSQL JDBC 写入时需要按 {@link java.sql.Types#OTHER} 传递，读取时兼容
 * 驱动返回 {@link UUID} 或字符串两种形式。</p>
 *
 * @author Hexascope Team
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class PostgresUuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, UUID parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, parameter, java.sql.Types.OTHER);
    }

    @Override
    public UUID getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return toUuid(rs.getObject(columnName), rs.wasNull());
    }

    @Override
    public UUID getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return toUuid(rs.getObject(columnIndex), rs.wasNull());
    }

    @Override
    public UUID getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return toUuid(cs.getObject(columnIndex), cs.wasNull());
    }

    private UUID toUuid(Object value, boolean wasNull) {
        if (wasNull || value == null) {
            return null;
        }
        if (value instanceof UUID uuid) {
            return uuid;
        }
        return UUID.fromString(value.toString());
    }
}
