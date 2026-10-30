# interview-guide-kt

智能 AI 面试官平台（Kotlin 重写版），与 Java 版功能对齐：简历解析与分析、文字面试、语音面试、知识库 RAG 问答、知识库题库与知识库面试、面试日程、多 LLM Provider 设置等。

## 功能特性

- **简历管理**：单文件/批量上传、Tika 文档解析、AI 结构化分析、分析报告 PDF 导出、失败重试与重新处理
- **文字面试**：简历题 + 方向题并行出题、追问、历史题目去重、逐题评估、面试报告与 PDF 导出（含书签与带圈编号）
- **语音面试**：Qwen3 Realtime ASR/TTS、WebSocket 实时字幕、暂停/恢复、阶段化面试流程、上下文压缩、异步评估
- **知识库**：批量上传、并发控制、向量化（可配置 Chunk 策略）、双路召回 RAG、来源标注、SSE 流式问答、RAG 聊天
- **知识库题库与面试**：从知识库异步生成题目、题目编辑/状态管理、按方向与追问容量发起知识库面试
- **面试日程**：JD 解析、日程创建/更新/状态流转、日历视图
- **多模型与设置**：DashScope、Kimi、DeepSeek、GLM、LM Studio 等 Provider 管理，API Key 加密持久化，聊天/Embedding 默认 Provider 切换，ASR/TTS 连通性测试
- **通用能力**：可重复维度限流、Redis Stream 异步任务与卡住任务恢复、Prometheus 指标、深浅色主题

## 技术栈

- 后端：Kotlin 2.2、Spring Boot 4.1、Spring AI 2.0、Spring Data JPA、Flyway、Redisson、SpringDoc
- 数据库：PostgreSQL + pgvector（向量维度 1024，COSINE）
- 缓存/异步：Redis / Redis Stream
- 存储/解析：S3 兼容对象存储（RustFS/MinIO）、Apache Tika
- AI：Spring AI OpenAI 兼容模式、spring-ai-agent-utils（SkillsTool）、阿里云 DashScope（Qwen ASR/TTS）
- 导出：iText 8
- 前端：React 18、TypeScript、Vite、Tailwind CSS 4

> 当前构建使用 Java 17 工具链（Kotlin JVM target 17）。虚拟线程默认关闭；在 Java 21+ 运行时可通过 `APP_VIRTUAL_THREADS_ENABLED=true` 开启。

## 项目结构

```
api/                                 后端服务
  src/main/kotlin/interview/guide/
    common/                          通用能力（AI、限流、异步、配置、异常、统一响应）
    infrastructure/                  技术基础设施（文件、导出、Redis、Mapper）
    modules/                         业务模块，每个模块自包含 MVC 分层
      interview/                     文字面试
      voiceinterview/                语音面试
      knowledgebase/                 知识库、RAG、知识库题库与面试
      resume/                        简历
      interviewschedule/             面试日程
      llmprovider/                   LLM Provider 与语音服务配置
  src/main/resources/
    db/migration/                    Flyway 迁移脚本
    prompts/                         StringTemplate Prompt 模板
    skills/                          面试方向 Skill（SKILL.md + 参考文件）
  docs/sql/                          分模块表结构文档
frontend/                            React 前端
```

## 快速开始

### 1. 启动依赖服务

```bash
cp .env.example .env
# 按需修改 POSTGRES_PASSWORD、RUSTFS_RPC_SECRET 等
docker compose -f docker-compose.dev.yml up -d
```

依赖：PostgreSQL + pgvector、Redis、RustFS（S3 兼容）。

### 2. 配置环境变量

根目录 `.env` 会被 `./gradlew :api:bootRun` 自动加载；IDE 启动时需要把同样的变量配置到运行环境。最小必填项：

```bash
AI_BAILIAN_API_KEY=your_dashscope_api_key
APP_AI_CONFIG_ENCRYPTION_KEY=<随机长密钥>
```

生成随机密钥：

```bash
cp .env.example .env
# macOS / Linux 均可
openssl rand -base64 32
# 把输出填入 .env 的 APP_AI_CONFIG_ENCRYPTION_KEY
```

仅本地临时调试、暂时没有密钥时，可以用固定 fallback（不要用于生产）：

```bash
APP_AI_CONFIG_REQUIRE_ENCRYPTION_KEY=false \
APP_AI_CONFIG_ALLOW_FALLBACK_ENCRYPTION_KEY=true \
AI_BAILIAN_API_KEY=dummy-key \
./gradlew :api:bootRun
```

常用可选项：

```bash
AI_MODEL=qwen3.5-flash
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456
REDIS_HOST=localhost
REDIS_PORT=6379
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=rustfsadmin
APP_STORAGE_SECRET_KEY=rustfsadmin
APP_STORAGE_BUCKET=interview-guide
```

Provider、语音面试、RAG、异步恢复等完整配置见 `api/src/main/resources/application.yml` 与 `.env.example`。

### 3. 启动后端

```bash
JAVA_HOME=<JDK 17+ 路径> ./gradlew :api:bootRun
```

- 默认端口：`8080`（可用 `SERVER_PORT` 覆盖）
- 数据库结构由 Flyway 自动迁移（`spring.flyway.enabled=true`），无需手工建表
- Swagger：`http://127.0.0.1:8080/swagger-ui.html`
- 健康检查：`http://127.0.0.1:8080/actuator/health`

### 4. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

默认端口：`5173`，`/api` 与 `/ws` 会代理到 `http://localhost:8080`（可用 `VITE_API_PROXY_TARGET` 覆盖）。

## 构建与测试

```bash
# 后端编译 / 打包
./gradlew :api:compileKotlin
./gradlew :api:build -x test

# 前端单测与构建
cd frontend
pnpm run test:interview-history
pnpm run test:question-generation
pnpm run test:interview-capacity
pnpm run test:interview-entry
pnpm run test:batch-upload
pnpm run test:voice-url
pnpm run build
```

## Docker 部署

```bash
cp .env.example .env
# 编辑 .env：AI_BAILIAN_API_KEY、APP_AI_CONFIG_ENCRYPTION_KEY 必填
docker compose up -d --build
```

- 前端：`http://localhost`
- 后端：`http://localhost:8080`
- 数据库迁移在应用启动时由 Flyway 自动执行

## 旧库升级说明

如果数据库是早期 Kotlin 版用 Hibernate `ddl-auto` 创建的（只有 `resumes`、`interview_sessions` 等 8 张表，没有 `flyway_schema_history`），直接启动会报：

```
Schema-validation: missing table [interview_schedule]
```

新版已提供幂等迁移 `V20260903_1__ensure_missing_tables_and_columns.sql`，会自动补齐缺失的表与列。直接用新版代码重启即可，无需手工建表。

如果 Flyway 历史里有失败记录（`success = false`），先清理失败记录再重启；不要在有大量已成功迁移的库上直接删整张历史表：

```sql
DELETE FROM flyway_schema_history WHERE success = false;
```

只有确认数据库可以丢弃、且没有需要保留的迁移记录时，才整体重置：

```sql
DROP TABLE IF EXISTS flyway_schema_history;
```

确需彻底重建本地开发库时：

```bash
dropdb interview_guide && createdb interview_guide
# 重启应用，Flyway 会执行完整 V1 + 增量迁移
```

## 数据库扩展权限

`vector`、`hstore`、`uuid-ossp` 三个扩展必须由**超级用户**安装一次。如果应用使用的数据库角色不是超级用户，会报：

```
ERROR: permission denied to create extension "vector"
建议：Must be superuser to create this extension.
```

先用超级用户连接目标数据库并执行：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS hstore;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

例如本机 Homebrew PostgreSQL 通常由当前系统用户（`puraz`）作为超级用户：

```bash
psql -d interview_guide -c 'CREATE EXTENSION IF NOT EXISTS vector;'
psql -d interview_guide -c 'CREATE EXTENSION IF NOT EXISTS hstore;'
psql -d interview_guide -c 'CREATE EXTENSION IF NOT EXISTS "uuid-ossp";'
```

也可以直接让应用使用超级用户连接（本地开发）：

```bash
POSTGRES_USER=puraz POSTGRES_PASSWORD= ./gradlew :api:bootRun
```

应用运行角色只需要建表和读写权限，不需要超级用户；扩展安装完成后即可用它正常执行 Flyway 迁移。

## 备注

- 文件上传上限与允许类型见 `application.yml` 中 `spring.servlet.multipart` 与 `app.resume.allowed-types`。
- 知识库上传单实例并发由 `app.knowledge-base-upload.max-concurrent` 控制。
- 调整向量化 Chunk 策略后需重新向量化知识库。
- 卡住任务恢复、限流、结构化输出重试等运维参数均集中在 `application.yml` 的 `app.*` 节点。
