-- =========================
-- 初始化扩展（按需安装）
-- =========================
CREATE EXTENSION IF NOT EXISTS hstore WITH SCHEMA public; -- Spring AI PgVectorStore 元数据支持
CREATE EXTENSION IF NOT EXISTS "uuid-ossp" WITH SCHEMA public; -- vector_store 主键生成
CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public; -- pgvector 向量检索
