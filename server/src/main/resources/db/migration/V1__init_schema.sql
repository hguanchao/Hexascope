-- =====================================================
-- ReqFlow Agent - 初始化数据库脚本（合并版）
-- 由 V1 ~ V7 迁移脚本按版本顺序合并而成，仅用于全新数据库初始化。
-- 日期: 2026-08-11
-- =====================================================

-- 文件说明：Flyway 数据库迁移脚本，定义 ReqFlow 业务表结构与初始化数据。
-- =====================================================
-- ReqFlow Agent - 初始化数据库脚本
-- 版本: V1
-- 日期: 2026-07-02
-- =====================================================

-- 启用 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

-- -----------------------------------------------------
-- 1. 审查记录表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS review_record (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requirement_id        VARCHAR(64)  NOT NULL,
    workspace_id          VARCHAR(64)  NOT NULL,
    team_id               VARCHAR(64)  NOT NULL,
    requirement_title     VARCHAR(512) NOT NULL,
    requirement_url       VARCHAR(1024),
    creator               VARCHAR(128) NOT NULL,
    total_score           INTEGER      NOT NULL CHECK (total_score >= 0 AND total_score <= 100),
    level                 VARCHAR(16)  NOT NULL CHECK (level IN ('EXCELLENT', 'GOOD', 'NEEDS_IMPROVEMENT', 'POOR')),
    dimension_scores      JSONB        NOT NULL,
    ai_suggestions        JSONB,
    improvement_suggestion TEXT,
    status                VARCHAR(32)  NOT NULL DEFAULT 'pending'
                          CHECK (status IN ('pending', 'approved', 'rejected', 'needs_revision')),
    reviewed_by           VARCHAR(128),
    ai_model_used         VARCHAR(64),
    ai_latency_ms         INTEGER,
    raw_prompt            TEXT,
    raw_ai_response       TEXT,
    retrigger_count       INTEGER      NOT NULL DEFAULT 0,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at          TIMESTAMP WITH TIME ZONE,
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    is_deleted            BOOLEAN      NOT NULL DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_review_team_status    ON review_record(team_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_requirement    ON review_record(requirement_id);
CREATE INDEX IF NOT EXISTS idx_review_creator        ON review_record(creator);
CREATE INDEX IF NOT EXISTS idx_review_score          ON review_record(total_score);
CREATE INDEX IF NOT EXISTS idx_review_created_at     ON review_record(created_at DESC);

-- -----------------------------------------------------
-- 2. 审查历史记录表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS review_history (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id           UUID NOT NULL,
    total_score         INTEGER NOT NULL,
    dimension_scores    JSONB   NOT NULL,
    ai_suggestions      JSONB,
    ai_model_used       VARCHAR(64),
    ai_latency_ms       INTEGER,
    reason              TEXT,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_history_review FOREIGN KEY (review_id) REFERENCES review_record(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_history_review_id ON review_history(review_id, created_at DESC);

-- -----------------------------------------------------
-- 3. 团队灰度配置表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS team_config (
    team_id               VARCHAR(64)  PRIMARY KEY,
    team_name             VARCHAR(128) NOT NULL,
    auto_review_enabled   BOOLEAN      NOT NULL DEFAULT TRUE,
    min_score_threshold   INTEGER      NOT NULL DEFAULT 55,
    review_model          VARCHAR(64)  DEFAULT 'qwen-plus',
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

-- -----------------------------------------------------
-- 4. TAPD 连接配置表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS tapd_connection (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id    VARCHAR(64)  NOT NULL,
    client_id       VARCHAR(256) NOT NULL,
    client_secret   TEXT         NOT NULL,
    webhook_secret  TEXT,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_tapd_workspace ON tapd_connection(workspace_id) WHERE is_active = TRUE;

-- -----------------------------------------------------
-- 5. 审计日志表
-- -----------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    operator      VARCHAR(128) NOT NULL,
    action        VARCHAR(64)  NOT NULL,
    target_type   VARCHAR(64),
    target_id     VARCHAR(128),
    detail        JSONB,
    ip_address    VARCHAR(64),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_audit_action_time ON audit_log(action, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operator    ON audit_log(operator, created_at DESC);

-- -----------------------------------------------------
-- 6. pgvector 向量存储表（Spring AI 自动创建，此处预留）
-- -----------------------------------------------------
-- spring_ai_vector_store 表由 Spring AI PgVectorStore 自动初始化
-- 参考配置: spring.ai.vectorstore.pgvector.initialize-schema=true

-- -----------------------------------------------------
-- 以下为原 V2~V7 增量脚本（按执行顺序合并）
-- -----------------------------------------------------

-- 文件说明：补齐创建需求表单信息在审查记录表中的持久化字段。

ALTER TABLE review_record
    ADD COLUMN IF NOT EXISTS requirement_description TEXT,
    ADD COLUMN IF NOT EXISTS priority VARCHAR(32);

-- 文件说明：支持需求先创建、AI 后台异步评分的审查状态与评分字段。

-- 评分改为异步后，reviewing 和 review_failed 记录可能暂时没有分数、等级和维度分。
ALTER TABLE review_record
    ALTER COLUMN total_score DROP NOT NULL,
    ALTER COLUMN level DROP NOT NULL,
    ALTER COLUMN dimension_scores DROP NOT NULL;

-- 状态约束需要同步接收评分中和评分失败两个新状态。
ALTER TABLE review_record
    DROP CONSTRAINT IF EXISTS review_record_status_check;

ALTER TABLE review_record
    ADD CONSTRAINT review_record_status_check
        CHECK (status IN ('reviewing', 'pending', 'approved', 'rejected', 'needs_revision', 'review_failed'));

-- 文件说明：新增 AI 审查工作流断点表，用于记录节点进度、失败快照和人工恢复次数。

CREATE TABLE IF NOT EXISTS review_workflow_checkpoint (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id            UUID         NOT NULL,
    requirement_id       VARCHAR(64)  NOT NULL,
    workflow_instance_id VARCHAR(64)  NOT NULL,
    current_node         VARCHAR(64)  NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    state_snapshot       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    error_message        TEXT,
    resume_count         INTEGER      NOT NULL DEFAULT 0,
    created_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    completed_at         TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_workflow_checkpoint_review
        FOREIGN KEY (review_id) REFERENCES review_record(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_checkpoint_instance
        UNIQUE (workflow_instance_id),
    CONSTRAINT review_workflow_checkpoint_status_check
        CHECK (status IN ('running', 'failed', 'waiting_human', 'completed'))
);

CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_review
    ON review_workflow_checkpoint(review_id, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_status
    ON review_workflow_checkpoint(status, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_workflow_checkpoint_requirement
    ON review_workflow_checkpoint(requirement_id, updated_at DESC);

-- 文件说明：知识库检索升级（P0）— pg_trgm 关键词检索扩展、content 索引、审查记录 RAG 过滤字段。

-- pg_trgm 提供 trigram 相似度检索，用于混合检索的关键词路（字符级匹配，对中文无需分词）。
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- vector_store 表由 Spring AI 启动时自动创建，迁移执行阶段可能尚不存在，
-- 因此这里仅在表已存在时创建索引；应用启动后由 PgIndexInitRunner 再次兜底确保索引存在。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vector_store') THEN
        CREATE INDEX IF NOT EXISTS idx_vector_store_content_trgm
            ON vector_store USING GIN (content gin_trgm_ops);
    END IF;
END $$;

-- 审查记录增加可选的 RAG 过滤条件（维度/来源/严重度），创建或重审需求时
-- 可以按需过滤评分标准召回，缺省为 NULL 表示不过滤（兼容旧行为）。
ALTER TABLE review_record ADD COLUMN IF NOT EXISTS kb_filters JSONB;
-- 文件说明：知识库版本管理（P1）— 知识源表、导入快照表、老库向量数据回填。

-- 1. 知识源表：一个 Excel 文件对应一条记录，文件内容变化时版本递增。
CREATE TABLE IF NOT EXISTS knowledge_source (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    file_name       VARCHAR(255) NOT NULL,
    file_hash       VARCHAR(64)  NOT NULL,
    version         INTEGER      NOT NULL DEFAULT 1,
    active          BOOLEAN      NOT NULL DEFAULT TRUE,
    imported_count  INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_knowledge_source_hash   ON knowledge_source(file_hash);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_active ON knowledge_source(active);

-- 2. 导入快照表：归档旧版本内容与变更对比摘要，旧文档删除后仍有历史可查。
CREATE TABLE IF NOT EXISTS knowledge_source_snapshot (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id     UUID NOT NULL,
    version       INTEGER NOT NULL,
    rows          JSONB NOT NULL DEFAULT '[]'::jsonb,
    diff_summary  JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_snapshot_source FOREIGN KEY (source_id) REFERENCES knowledge_source(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_snapshot_source ON knowledge_source_snapshot(source_id, version DESC);

-- 3. 老库回填：迁移前已存在的知识片段统一标记为 legacy 来源且 active，
-- 保证检索侧的 source_active 过滤对新旧数据口径一致（值存字符串 "true"，与检索过滤匹配）。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_name = 'vector_store') THEN
        UPDATE vector_store
        SET metadata = (COALESCE(metadata::jsonb, '{}'::jsonb)
                || '{"source_active": "true", "source_id": "legacy", "source_version": 1}'::jsonb)::json
        WHERE metadata IS NULL OR NOT (metadata::jsonb ? 'source_active');
    END IF;
END $$;
-- 文件说明：知识库检索追踪与召回评估（P2）— trace / eval 表结构。

-- 1. 检索追踪表：每次 AI 评分检索打点一行，记录 query 列表、候选、精排结果与耗时。
CREATE TABLE IF NOT EXISTS kb_search_trace (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    review_id       UUID,
    queries         JSONB NOT NULL DEFAULT '[]'::jsonb,
    candidates      JSONB NOT NULL DEFAULT '[]'::jsonb,
    selected        JSONB NOT NULL DEFAULT '[]'::jsonb,
    retrieval_ms    INTEGER,
    rerank_ms       INTEGER,
    total_ms        INTEGER,
    hybrid_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trace_review  ON kb_search_trace(review_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_trace_created ON kb_search_trace(created_at);

-- 2. 评估用例表（金标）：expected_doc_ids 存知识片段 id 列表。
-- 导入评分表时会按 (source, row_num) 自动 upsert 自标注用例；人工用例该两列为 NULL。
CREATE TABLE IF NOT EXISTS kb_eval_case (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    query            TEXT NOT NULL,
    expected_doc_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    dimension        VARCHAR(64),
    source           VARCHAR(64),
    row_num          INTEGER,
    note             TEXT,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_eval_case_auto UNIQUE (source, row_num)
);

CREATE INDEX IF NOT EXISTS idx_eval_case_dimension ON kb_eval_case(dimension);

-- 3. 评估运行结果表：每次 run 一个用例一条记录，config 记录检索配置，metrics 记录指标。
CREATE TABLE IF NOT EXISTS kb_eval_run (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id      UUID NOT NULL,
    config       JSONB NOT NULL DEFAULT '{}'::jsonb,
    metrics      JSONB NOT NULL DEFAULT '{}'::jsonb,
    executed_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_eval_run_case FOREIGN KEY (case_id) REFERENCES kb_eval_case(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_eval_run_case ON kb_eval_run(case_id, executed_at DESC);