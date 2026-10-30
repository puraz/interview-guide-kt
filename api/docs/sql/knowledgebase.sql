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
    vector_status VARCHAR(20) DEFAULT 'PENDING', -- 向量化状态：PENDING/PROCESSING/COMPLETED/FAILED
    vector_error VARCHAR(500), -- 向量化错误信息
    chunk_count INT DEFAULT 0, -- 分块数量
    vector_config TEXT, -- 向量化 Chunk 策略快照
    vectorized_at TIMESTAMP, -- 向量化完成时间
    vector_updated_at TIMESTAMP, -- 最后一次有效进展时间（状态变化与心跳都推进）
    vector_recovery_count INT NOT NULL DEFAULT 0, -- 卡住任务恢复次数
    vector_attempt_id VARCHAR(36), -- 当前执行代次 fencing token
    question_gen_status VARCHAR(20) NOT NULL DEFAULT 'NONE', -- 题目生成状态：NONE/QUEUED/PROCESSING/COMPLETED/FAILED
    question_gen_error VARCHAR(500), -- 题目生成错误信息
    question_gen_task_id VARCHAR(36), -- 题目生成任务ID
    question_gen_config TEXT, -- 题目生成配置快照
    question_gen_message VARCHAR(500), -- 题目生成进度消息
    question_gen_saved_count INT NOT NULL DEFAULT 0, -- 已保存题目数
    question_gen_skipped_count INT NOT NULL DEFAULT 0, -- 已跳过题目数
    question_gen_updated_at TIMESTAMP -- 题目生成状态更新时间
);

CREATE INDEX IF NOT EXISTS idx_kb_category ON knowledge_bases (category);
CREATE INDEX IF NOT EXISTS idx_kb_question_gen_status_updated ON knowledge_bases (question_gen_status, question_gen_updated_at);
CREATE INDEX IF NOT EXISTS idx_kb_vector_status_updated ON knowledge_bases (vector_status, vector_updated_at);

-- 知识库题库表（知识库面试题目）
CREATE TABLE IF NOT EXISTS knowledge_base_questions (
    id BIGSERIAL PRIMARY KEY,
    knowledge_base_id BIGINT REFERENCES knowledge_bases(id) ON DELETE CASCADE, -- 关联知识库ID
    skill_id VARCHAR(64) NOT NULL, -- 面试方向 Skill ID
    category VARCHAR(64), -- 题目分类
    difficulty VARCHAR(16), -- 难度：JUNIOR/MID/SENIOR
    type VARCHAR(64), -- 题目类型
    question TEXT NOT NULL, -- 题目内容
    topic_summary VARCHAR(300), -- 主题摘要
    reference_answer TEXT, -- 参考答案
    key_points_json TEXT, -- 要点JSON
    scoring_rubric TEXT, -- 评分细则
    follow_ups_json TEXT, -- 追问JSON
    source_context TEXT, -- 来源上下文
    kb_content_hash VARCHAR(64), -- 知识库内容哈希（用于识别过期题目）
    status VARCHAR(20) NOT NULL, -- 状态：DRAFT/ACTIVE/ARCHIVED/STALE
    created_at TIMESTAMP NOT NULL, -- 创建时间
    updated_at TIMESTAMP NOT NULL, -- 更新时间
    CONSTRAINT knowledge_base_questions_status_check
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED', 'STALE'))
);

CREATE INDEX IF NOT EXISTS idx_kb_question_kb_status ON knowledge_base_questions (knowledge_base_id, status);
CREATE INDEX IF NOT EXISTS idx_kb_question_skill_difficulty ON knowledge_base_questions (skill_id, difficulty);

-- RAG聊天会话表
CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id BIGSERIAL PRIMARY KEY, -- 会话ID
    title VARCHAR(255) NOT NULL, -- 会话标题
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- 会话状态：ACTIVE/ARCHIVED
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
    type VARCHAR(20) NOT NULL, -- 消息类型：USER/ASSISTANT
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
    id UUID DEFAULT public.uuid_generate_v4() PRIMARY KEY, -- 文档ID
    content TEXT, -- 文档内容
    metadata JSON, -- 元数据
    embedding vector(1024) -- 向量数据（与配置维度一致）
);

CREATE INDEX IF NOT EXISTS spring_ai_vector_index
    ON vector_store USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_vector_store_metadata_kb_id
    ON vector_store ((metadata->>'kb_id'));
