# interview-guide-kt

智能 AI 面试官平台，提供简历解析与分析、模拟面试、知识库检索问答（RAG）、RAG 聊天等能力。后端基于 Kotlin + Spring Boot，前端基于 React + Vite。

## 功能概览

- 简历分析：上传简历、结构化解析、分析报告、PDF 导出与重试
- 模拟面试：创建会话、答题、生成面试报告、PDF 导出
- 知识库：文件上传、向量化、检索问答、SSE 流式回答
- RAG 聊天：会话管理、消息流式响应、知识库切换

## 技术栈

- 后端：Kotlin、Spring Boot 4、Spring AI、JPA、Redis、PostgreSQL + pgvector
- 前端：React、Vite、Tailwind CSS、Axios
- 存储：S3 兼容对象存储（RustFS/MinIO 等）

## 目录结构

- `api/` 后端服务
- `frontend/` 前端应用
- `api/docs/sql/` 数据库初始化与表结构脚本

## 快速启动

### 1. 准备依赖服务

- PostgreSQL 17+（建议开启 `pgvector` 扩展）
- Redis
- S3 兼容对象存储（RustFS/MinIO/OSS 等）

### 2. 数据库初始化

1. 创建数据库（示例：`interview_guide`）
2. 执行 `pgvector` 扩展脚本：

```sql
-- api/docs/sql/init.sql
CREATE EXTENSION IF NOT EXISTS vector;
```

3. 参考以下脚本初始化表结构：

- `api/docs/sql/resume.sql`
- `api/docs/sql/interview.sql`
- `api/docs/sql/knowledgebase.sql`

说明：`api/src/main/resources/application.yml` 中默认 `spring.jpa.hibernate.ddl-auto` 为 `create`，首次启动后建议改为 `update` 或生产环境手动管理表结构。

### 3. 配置环境变量

后端配置集中在 `api/src/main/resources/application.yml`，可通过以下环境变量覆盖：

```bash
# PostgreSQL
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
POSTGRES_DB=interview_guide
POSTGRES_USER=postgres
POSTGRES_PASSWORD=123456

# Redis
REDIS_HOST=localhost
REDIS_PORT=6379

# AI（阿里云百炼 / OpenAI 兼容）
AI_BAILIAN_API_KEY=your_api_key
AI_MODEL=qwen-plus

# S3 兼容存储
APP_STORAGE_ENDPOINT=http://localhost:9000
APP_STORAGE_ACCESS_KEY=your_access_key
APP_STORAGE_SECRET_KEY=your_secret_key
APP_STORAGE_BUCKET=interview-guide
APP_STORAGE_REGION=us-east-1

# CORS
CORS_ALLOWED_ORIGINS=http://localhost:5173,http://localhost:5174,http://localhost:80
```

### 4. 启动后端

```bash
./gradlew :api:bootRun
```

默认端口：`8080`

### 5. 启动前端

```bash
cd frontend
pnpm install
pnpm dev
```

默认端口：`5173`

## 访问入口

- Swagger 文档：`http://127.0.0.1:8080/swagger-ui.html`
- Druid 监控：`http://127.0.0.1:8080/druid`

## 构建与部署

### 后端构建

```bash
./gradlew :api:build
```

### 前端构建

```bash
cd frontend
pnpm build
```

### 前端 Docker 部署

`frontend/Dockerfile` 使用多阶段构建，最终由 Nginx 托管静态文件。`frontend/nginx.conf` 中默认将 `/api/` 代理到 `http://app:8080`，如需改动请调整 Nginx 配置。

## 备注

- 文件上传上限与允许类型见 `application.yml` 中 `spring.servlet.multipart` 与 `app.resume.allowed-types`。
- 如果接入其他模型或向量库，请同步调整 `spring.ai` 与 `spring.ai.vectorstore.pgvector` 配置。
