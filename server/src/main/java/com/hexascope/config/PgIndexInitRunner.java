/*
 * 文件说明：启动后兜底创建知识库关键词检索索引。
 */
package com.hexascope.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 知识库关键词索引兜底初始化。
 *
 * <p>vector_store 表由 Spring AI 启动时自动创建，Flyway 迁移阶段该表可能还不存在，
 * 因此 V5 迁移里建索引的语句带了存在性判断。这里在全部 Bean 初始化完成后再次执行
 * 幂等建索引，保证新库也能拿到 pg_trgm 索引加速。</p>
 *
 * @author Hexascope Team
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PgIndexInitRunner implements ApplicationRunner {

    private static final String CREATE_TRGM_INDEX_SQL =
            "CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm "
                    + "ON vector_store USING GIN (content gin_trgm_ops)";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbcTemplate.execute(CREATE_TRGM_INDEX_SQL);
            log.info("知识库关键词索引已确认存在: idx_vector_store_content_trgm");
        } catch (Exception e) {
            // 建索引失败不阻断启动；混合检索会自动退化为纯向量检索。
            log.warn("创建知识库关键词索引失败（混合检索将退化为纯向量检索）: {}", e.getMessage());
        }
    }
}