/*
 * 文件说明：Spring Boot 配置类，集中声明安全、跨域、Redis 或 MyBatis 等基础设施。
 */
package com.hexascope.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.hexascope.common.PostgresLocalDateTimeTypeHandler;
import com.hexascope.common.PostgresUuidTypeHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MyBatis-Plus 配置
 *
 * <p>集中配置分页插件、PostgreSQL 特殊类型处理器，以及实体创建时间、
 * 更新时间的自动填充规则。</p>
 *
 * @author Hexascope Team
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件
     *
     * <p>Mapper 分页查询依赖该拦截器生成 PostgreSQL 方言的分页 SQL。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }

    /**
     * 注册 PostgreSQL TIMESTAMPTZ 到 LocalDateTime 的映射。
     *
     * <p>项目实体使用 {@link LocalDateTime} 和 {@link UUID}，PostgreSQL 对应字段是
     * 时间戳和 uuid 类型。这里统一注册类型处理器，避免每个 Mapper 单独处理转换。</p>
     */
    @Bean
    public ConfigurationCustomizer postgresTypeHandlerCustomizer() {
        return configuration -> {
            PostgresLocalDateTimeTypeHandler handler = new PostgresLocalDateTimeTypeHandler();
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, handler);
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, JdbcType.TIMESTAMP_WITH_TIMEZONE, handler);
            configuration.getTypeHandlerRegistry().register(LocalDateTime.class, JdbcType.TIMESTAMP, handler);

            PostgresUuidTypeHandler uuidHandler = new PostgresUuidTypeHandler();
            configuration.getTypeHandlerRegistry().register(UUID.class, uuidHandler);
            configuration.getTypeHandlerRegistry().register(UUID.class, JdbcType.OTHER, uuidHandler);
        };
    }

    /**
     * 自动填充处理器（创建时间/更新时间）
     *
     * <p>插入时填充 createdAt 和 updatedAt，更新时只刷新 updatedAt，保证实体时间字段
     * 在业务代码中不用重复手工赋值。</p>
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, LocalDateTime.now());
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
