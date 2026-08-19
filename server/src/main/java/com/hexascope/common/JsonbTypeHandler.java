/*
 * 文件说明：后端公共基础组件，提供统一响应、分页、工具方法或类型处理能力。
 */
package com.hexascope.common;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/**
 * PostgreSQL JSONB 类型处理器
 *
 * <p>将 Java Map 与 PostgreSQL jsonb 类型互相转换。
 * 使用泛型通配符 {@code Map<String, ?>} 兼容 {@code Map<String, Integer>}、
 * {@code Map<String, Object>} 等多种 value 类型。</p>
 *
 * @author Hexascope Team
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@MappedTypes(Map.class)
public class JsonbTypeHandler extends BaseTypeHandler<Map> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Map parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setObject(i, JSONUtil.toJsonStr(parameter), java.sql.Types.OTHER);
    }

    @Override
    public Map getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }

    @Override
    public Map getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    @Override
    public Map getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }

    private Map parseJson(String json) {
        if (StrUtil.isBlank(json)) {
            return Map.of();
        }
        return (Map) JSONUtil.parse(json);
    }
}
