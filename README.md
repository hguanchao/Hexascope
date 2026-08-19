# Hexascope · 六维镜

> TAPD 需求评审与评分 AI Agent —— **六维透视，尺度分明**

Hexascope 是一个面向 TAPD 需求（Requirement）的智能评审与评分平台。它把需求放进一座「六维镜」：AI 依据评分标准表，从完整性、明确性、可行性、价值对齐、可测试性、格式规范六个维度对被评需求打分评级，并输出可执行的改进建议。

知识库持续沉淀评分标准，支持混合检索与精排；每次评审的命中来源可回溯、检索质量可评估，形成「标准入库 → 检索召回 → 评审打分 → 评估反哺」的完整闭环。

---

## 特性

- **六维评审**：按评分标准表对需求逐维打分（满分 10 分，加权合成总分），输出等级、评分依据与改进建议。
- **断点续跑工作流**：评审按「评分 → 修复输出 → 校验 → 等待人工确认」多节点执行，检查点持久化，中断或失败可 resume，不重复执行已完成节点。
- **知识库混合检索**：评分标准文档分块、向量化入库；检索采用「pgvector 向量召回 + pg_trgm 关键词召回 + RRF 融合」策略，可开关 reranker 精排。
- **评估与溯源**：内置检索评估用例集（eval cases），跟踪每次检索命中来源（trace）与版本快照，量化检索质量并支持版本对比。
- **稳定可控**：AI 调用接入 resilience4j 熔断、重试与限流（Redis 分布式），模型异常不拖垮评审流程。
- **开箱可观测**：Actuator 健康/指标/Prometheus 导出，Swagger/OpenAPI 在线接口文档。

---

## 架构总览

```text
┌──────────────┐   /api   ┌───────────────────────────┐
│   React SPA  │ ───────► │   Spring Boot (18080)     │
│  Vite + antd │ ◄─────── │  /api/v1  统一前缀         │
└──────────────┘          │                           │
                          │  controller ─► service    │
                          │      │                    │
                          │      ├─ ai/ 评审引擎       │
                          │      │   ├─ workflow      │ 断点续跑
                          │      │   ├─ kb + rerank   │ 混合检索/精排
                          │      │   └─ parser/prompt │ 输出修复
                          │      │                    │
                          │      ├─ MyBatis-Plus      │
                          │      └─ resilience4j      │ 熔断/重试/限流
                          └──────┬─────────┬──────┬───┘
                                 │         │      │
                        ┌────────▼──┐ ┌────▼────┐ ┌▼──────┐
                        │ PostgreSQL│ │ pgvector│ │ Redis │
                        │ 5432 通用 │ │ 5433业务│ │ 6379  │
                        └───────────┘ └─────────┘ └───────┘
                        业务表/向量表   向量+trgm   限流锁
```

前端 `http://localhost:15173`，Vite 将 `/api` 代理至后端 `http://localhost:18080`。

---

## 核心流程

### 评审工作流

创建需求接口会**同步返回**「评分中」状态，真正的 AI 评审在事务提交后异步执行，避免大模型耗时阻塞前端。工作流共 4 个节点，每步结果落库为检查点：

| 节点 | 说明 |
| --- | --- |
| `scoreRequirement` | 组装需求上下文，调用模型按六维打分 |
| `repairOutput` | 对模型输出做结构修复（JSON 解析兜底） |
| `validateResult` | 校验评分结论的完整性与合法性 |
| `waitForHumanConfirmation` | 等待人工确认，进入最终人工态 |

失败节点可通过 `POST /requirements/{id}/workflow/resume` 续跑；已完成或等待人工确认的工作流不会重复执行。

### 知识库检索链路

```text
需求描述 ──► 多 query 展开/截断
            ├─► pgvector 向量召回（HNSW + 余弦）
            ├─► pg_trgm 关键词召回
            └─► RRF 融合 ──► [可选] reranker 精排 ──► 组装进 Prompt
```

检索命中的评分标准片段会记录 `trace`（溯源），用于评估检索质量与问题定位。

### 状态机

```text
pending ─► reviewing ─► needs_revision ◄─┐
   │            │                        │
   │            ├─► approved             │
   │            ├─► rejected             │
   │            └─► review_failed ─►(resume)
```

---

## 技术栈

| 端 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8 · spring-ai-alibaba 1.1.2.3 · MyBatis-Plus · Flyway · resilience4j · Hutool |
| 前端 | React 18 · Vite 6 · TypeScript · Ant Design 5 · Zustand · ECharts |
| 数据 | PostgreSQL 16 + pgvector（向量检索）· Redis 7（限流锁） |
| 模型 | SiliconFlow（OpenAI 兼容）：对话 `Qwen/Qwen3.5-4B` · 向量 `BAAI/bge-m3`(1024 维) · 精排 `BAAI/bge-reranker-v2-m3` |

---

## 项目结构

```text
hexascope/
├── docs/
│   └── 评分标准表_Hexascope_Scoring_Rubric.xlsx   # 评分标准表（知识库导入用）
├── server/                        # 后端（Spring Boot, Maven）
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hexascope/
│       │   ├── ai/
│       │   │   ├── kb/           # 分块、混合检索、过滤、评估指标
│       │   │   ├── parser/       # 模型输出解析与修复（Fallback 打分兜底）
│       │   │   ├── prompt/       # 评审提示词模板
│       │   │   ├── rerank/       # reranker 客户端与配置
│       │   │   └── workflow/     # 评审工作流编排（断点续跑）
│       │   ├── config/           # Security、MyBatis-Plus、pgvector 索引初始化
│       │   ├── controller/       # REST 接口
│       │   ├── mapper/           # MyBatis Mapper
│       │   ├── model/            # dto / entity / enums / vo
│       │   └── service/          # 业务服务
│       └── resources/
│           ├── db/migration/     # Flyway 迁移脚本
│           └── mapper/           # MyBatis XML
└── web/                          # 前端（React + Vite）
    └── src/
        ├── components/           # Layout、ScoreBadge、RadarChart、TrendChart
        ├── pages/                # Dashboard、ReviewList、ReviewDetail、KnowledgeBase
        ├── services/             # 接口调用封装
        ├── store/                # Zustand 状态
        └── types/ utils/         # 类型定义、常量
```

---

## 快速开始

### 环境要求

- JDK 21、Maven 3.9+
- Node.js 18+、npm
- Docker（本地基础设施）

### 1. 启动基础设施

仓库未内置 `docker-compose.yml`（本地环境差异大），按需自行编排，或直接使用等价命令：

```bash
# PostgreSQL（pgvector 实例，业务表所在）
docker run -d --name pgvector -p 5433:5432 \
  -e POSTGRES_PASSWORD=your_pgvector_password \
  -e POSTGRES_DB=req-flow-agent \
  pgvector/pgvector:pg16

# Redis
docker run -d --name redis -p 6379:6379 \
  -e REDIS_PASSWORD=your_redis_password redis:7-alpine \
  redis-server --requirepass your_redis_password
```

> 数据库名称、连接地址、账号密码以 `server/src/main/resources/application-dev.yml` 为准（该文件已加入 `.gitignore`，按本地环境配置）。

### 2. 启动后端

```bash
cd server
mvn spring-boot:run
```

- 服务地址：`http://localhost:18080/api/v1`
- Swagger UI：`http://localhost:18080/api/v1/swagger-ui.html`
- 健康检查：`http://localhost:18080/api/v1/actuator/health`

首次启动 Flyway 自动执行 `V1__init_schema.sql` 建表；pgvector 表结构与 HNSW 索引启动时自动初始化。

### 3. 启动前端

```bash
cd web
npm install
npm run dev
```

- 访问 `http://localhost:15173`，`/api` 请求自动代理到 `http://localhost:18080`

---

## API 一览

统一响应包装 `Result<T>`（code / message / data），鉴权当前阶段全放行。

### 需求评审 `/requirements`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/requirements` | 创建需求并**异步**触发 AI 评分，立即返回「评分中」 |
| GET | `/requirements` | 分页查询审查列表（团队、创建人、状态、分数区间、时间过滤） |
| GET | `/requirements/{id}` | 评审详情：原始需求、总分、六维分、AI 建议、证据、历史重评 |
| POST | `/requirements/retrigger` | 重新触发某条需求的 AI 审查 |
| POST | `/requirements/{id}/status` | 人工更新状态（通过 / 打回 / 需修改），仅限已完成 AI 评分 |
| POST | `/requirements/{id}/workflow/resume` | 续跑失败的评审工作流 |

### 知识库 `/knowledge-base`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/knowledge-base/stats` | 知识库统计概览 |
| GET | `/knowledge-base/sources` | 知识来源列表 |
| GET | `/knowledge-base/documents` | 文档列表 |
| POST | `/knowledge-base/import` | 导入评分标准文档（xlsx / 文本） |
| DELETE | `/knowledge-base/documents/{id}` | 删除文档 |
| GET | `/knowledge-base/traces` | 检索命中溯源记录 |
| POST | `/knowledge-base/traces/clean` | 清空溯源记录 |
| GET | `/knowledge-base/eval/cases` | 评估用例列表 |
| POST | `/knowledge-base/eval/cases` | 创建评估用例 |
| DELETE | `/knowledge-base/eval/cases/{id}` | 删除评估用例 |
| POST | `/knowledge-base/eval/run-all` | 批量运行检索评估 |
| GET | `/knowledge-base/eval/runs` | 评估运行历史 |
| GET | `/knowledge-base/eval/summary` | 评估结果汇总 |

### 统计 `/reviews/stats`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/reviews/stats/overview` | 总览统计 |
| GET | `/reviews/stats/trend` | 评审趋势 |
| GET | `/reviews/stats/team` | 团队维度统计 |

---

## 配置说明

### AI 与向量库（`application-dev.yml`）

- **模型接口**：OpenAI 兼容协议，`spring.ai.openai.base-url` 指定（默认 SiliconFlow），`spring.ai.openai.api-key` 配置密钥。
- **对话模型**：`Qwen/Qwen3.5-4B` — 生成评审意见与评分。
- **向量模型**：`BAAI/bge-m3` — 输出 1024 维向量，对应表字段 `vector(1024)`。
- **精排模型**：`BAAI/bge-reranker-v2-m3` — 复用对话模型密钥。
- **知识库检索** `hexascope.kb.*`：`top-k` 召回数量、`similarity-threshold` 相似度阈值、查询截断长度与多 query 展开数。
- **评审入模** `hexascope.review.*`：发给模型的需求工作副本长度（原始需求仍完整入库）。
- **Reranker** `hexascope.reranker.*`：开关与精排参数。

### 评审维度与权重

| 维度 | 权重 |
| --- | --- |
| 完整性 Completeness | 25% |
| 明确性 Clarity | 20% |
| 价值对齐 Value Alignment | 20% |
| 可行性 Feasibility | 15% |
| 可测试性 Testability | 10% |
| 格式规范 Format | 10% |

---

## 数据模型

Flyway 管理，核心表（pgvector 实例 `req-flow-agent` 库）：

| 分组 | 表 | 说明 |
| --- | --- | --- |
| 评审 | `review_record` | 需求评审主表（原始需求、总分、维度分、状态） |
| 评审 | `review_history` | 评审历史 / 重评记录 |
| 评审 | `review_workflow_checkpoint` | 工作流断点（节点、状态、上下文） |
| 知识库 | `knowledge_source` / `knowledge_source_snapshot` | 知识来源与版本快照 |
| 知识库 | `kb_search_trace` | 检索命中溯源 |
| 评估 | `kb_eval_case` / `kb_eval_run` | 评估用例与运行记录 |
| 系统 | `audit_log` | 审计日志 |
| 系统 | `tapd_connection` / `team_config` | TAPD 连接与团队配置（预留） |

- 主键由 MyBatis-Plus 生成 UUID，删除采用逻辑删除。
- 实体自定义类型 `JsonbTypeHandler`、`PostgresUuidTypeHandler` 等位于 `common/`。

---

## 稳定性与安全

- **认证**：当前阶段 Spring Security 对全部请求放行（`permitAll` + 关闭 CSRF），JWT 认证规划中，**请勿直接暴露公网**。
- **AI 调用防护**：熔断（滑动窗口）、重试（指数退避）、Redis 分布式限流。
- **密钥管理**：所有凭据（数据库、Redis、模型 API Key）仅存于被 `.gitignore` 忽略的 `application-dev.yml`，严禁提交仓库。

---

## 排障

| 现象 | 处理 |
| --- | --- |
| 评审一直 `reviewing` | 查看后端日志模型调用是否超时/熔断，`/requirements/{id}/workflow/resume` 续跑 |
| 检索召回为空 | 检查 `similarity-threshold` 是否过高、知识库是否已导入、pgvector 表是否初始化 |
| 向量维度报错 | 确认 embedding 模型输出维度与库表 `vector(1024)` 一致（换模型需同步重建列） |
| 端口被占 | 后端 `server.port`、前端 Vite `server.port` 分别调整，代理目标同步改 |
| Flyway 校验失败 | 数据库结构被手工改动导致 checksum 变化，按实际取舍 `repair` 或重建库 |

---

## 相关文档

- 评分标准表：`docs/评分标准表_Hexascope_Scoring_Rubric.xlsx`（导入知识库后供 AI 检索打分）
- 接口契约：运行时 `http://localhost:18080/api/v1/swagger-ui.html`