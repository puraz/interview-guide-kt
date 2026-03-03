-- =========================
-- 知识库与RAG聊天模块表结构
-- =========================

-- 知识库表
CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY, -- 主键ID
    file_hash VARCHAR(64) NOT NULL UNIQUE, -- 文件哈希，用于去重
    name VARCHAR(255) NOT NULL, -- 知识库名称
    category VARCHAR(100), -- 分类
    original_filename VARCHAR(255) NOT NULL, -- 原始文件名
    file_size BIGINT, -- 文件大小
    content_type VARCHAR(255), -- 文件类型
    storage_key VARCHAR(500), -- 存储Key
    storage_url VARCHAR(1000), -- 存储URL
    uploaded_at TIMESTAMP NOT NULL, -- 上传时间
    last_accessed_at TIMESTAMP, -- 最后访问时间
    access_count INT DEFAULT 1, -- 访问次数
    question_count INT DEFAULT 0, -- 提问次数
    vector_status VARCHAR(20) DEFAULT 'PENDING', -- 向量化状态
    vector_error VARCHAR(500), -- 向量化错误信息
    chunk_count INT DEFAULT 0 -- 分块数量
);

CREATE INDEX IF NOT EXISTS idx_kb_category ON knowledge_bases (category);

-- RAG聊天会话表
CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id BIGSERIAL PRIMARY KEY, -- 会话ID
    title VARCHAR(255) NOT NULL, -- 会话标题
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- 会话状态
    created_at TIMESTAMP NOT NULL, -- 创建时间
    updated_at TIMESTAMP, -- 更新时间
    message_count INT DEFAULT 0, -- 消息数量
    is_pinned BOOLEAN DEFAULT FALSE -- 是否置顶
);

CREATE INDEX IF NOT EXISTS idx_rag_session_updated ON rag_chat_sessions (updated_at);

-- RAG聊天消息表
CREATE TABLE IF NOT EXISTS rag_chat_messages (
    id BIGSERIAL PRIMARY KEY, -- 消息ID
    session_id BIGINT NOT NULL REFERENCES rag_chat_sessions(id) ON DELETE CASCADE, -- 会话ID
    type VARCHAR(20) NOT NULL, -- 消息类型
    content TEXT NOT NULL, -- 消息内容
    message_order INT NOT NULL, -- 消息顺序
    created_at TIMESTAMP NOT NULL, -- 创建时间
    updated_at TIMESTAMP, -- 更新时间
    completed BOOLEAN DEFAULT TRUE -- 是否完成
);

CREATE INDEX IF NOT EXISTS idx_rag_message_session ON rag_chat_messages (session_id);
CREATE INDEX IF NOT EXISTS idx_rag_message_order ON rag_chat_messages (session_id, message_order);

-- RAG会话与知识库关联表
CREATE TABLE IF NOT EXISTS rag_session_knowledge_bases (
    session_id BIGINT NOT NULL REFERENCES rag_chat_sessions(id) ON DELETE CASCADE, -- 会话ID
    knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE, -- 知识库ID
    PRIMARY KEY (session_id, knowledge_base_id)
);

CREATE INDEX IF NOT EXISTS idx_rag_session_kb_kb_id ON rag_session_knowledge_bases (knowledge_base_id);

-- 向量存储表（Spring AI PgVectorStore 默认表）
-- 注意：需要安装 pgvector 扩展
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY, -- 文档ID
    content TEXT, -- 文档内容
    metadata JSONB, -- 元数据
    embedding vector(1024) -- 向量数据（与配置维度一致）
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding_hnsw
    ON vector_store USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_kb_id
    ON vector_store ((metadata->>'kb_id'));
