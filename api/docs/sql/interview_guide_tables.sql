-- 参考 interview-guide 实体的表结构定义

-- 创建数据库用户（角色）与授权
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'postgres') THEN
        CREATE ROLE postgres WITH LOGIN PASSWORD '123456';
    END IF;
END $$;

CREATE DATABASE IF NOT EXISTS interview_guide;

GRANT ALL PRIVILEGES ON DATABASE interview_guide TO postgres;
GRANT ALL PRIVILEGES ON SCHEMA public TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON TABLES TO postgres;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL ON SEQUENCES TO postgres;

CREATE TABLE IF NOT EXISTS resumes (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL UNIQUE,
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(255),
    storage_key VARCHAR(500),
    storage_url VARCHAR(1000),
    resume_text TEXT,
    uploaded_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP,
    access_count INTEGER DEFAULT 0,
    analyze_status VARCHAR(20),
    analyze_error VARCHAR(500)
);
COMMENT ON TABLE resumes IS '简历表';

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_hash ON resumes (file_hash);

CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL,
    overall_score INTEGER,
    content_score INTEGER,
    structure_score INTEGER,
    skill_match_score INTEGER,
    expression_score INTEGER,
    project_score INTEGER,
    summary TEXT,
    strengths_json TEXT,
    suggestions_json TEXT,
    analyzed_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_resume_analyses_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id)
);
COMMENT ON TABLE resume_analyses IS '简历评测结果表';

CREATE TABLE IF NOT EXISTS interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    resume_id BIGINT NOT NULL,
    total_questions INTEGER,
    current_question_index INTEGER DEFAULT 0,
    status VARCHAR(20),
    questions_json TEXT,
    overall_score INTEGER,
    overall_feedback TEXT,
    strengths_json TEXT,
    improvements_json TEXT,
    reference_answers_json TEXT,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    evaluate_status VARCHAR(20),
    evaluate_error VARCHAR(500),
    CONSTRAINT fk_interview_sessions_resume_id FOREIGN KEY (resume_id) REFERENCES resumes (id)
);
COMMENT ON TABLE interview_sessions IS '面试会话表';

CREATE INDEX IF NOT EXISTS idx_interview_session_resume_created ON interview_sessions (resume_id, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_resume_status_created ON interview_sessions (resume_id, status, created_at);

CREATE TABLE IF NOT EXISTS interview_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    question_index INTEGER,
    question TEXT,
    category VARCHAR(255),
    user_answer TEXT,
    score INTEGER,
    feedback TEXT,
    reference_answer TEXT,
    key_points_json TEXT,
    answered_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_interview_answers_session_id FOREIGN KEY (session_id) REFERENCES interview_sessions (id),
    CONSTRAINT uk_interview_answer_session_question UNIQUE (session_id, question_index)
);
COMMENT ON TABLE interview_answers IS '面试答案表';

CREATE INDEX IF NOT EXISTS idx_interview_answer_session_question ON interview_answers (session_id, question_index);

CREATE TABLE IF NOT EXISTS knowledge_bases (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    category VARCHAR(100),
    original_filename VARCHAR(255) NOT NULL,
    file_size BIGINT,
    content_type VARCHAR(255),
    storage_key VARCHAR(500),
    storage_url VARCHAR(1000),
    uploaded_at TIMESTAMP NOT NULL,
    last_accessed_at TIMESTAMP,
    access_count INTEGER DEFAULT 0,
    question_count INTEGER DEFAULT 0,
    vector_status VARCHAR(20),
    vector_error VARCHAR(500),
    chunk_count INTEGER DEFAULT 0
);
COMMENT ON TABLE knowledge_bases IS '知识库表';

CREATE UNIQUE INDEX IF NOT EXISTS idx_kb_hash ON knowledge_bases (file_hash);
CREATE INDEX IF NOT EXISTS idx_kb_category ON knowledge_bases (category);

CREATE TABLE IF NOT EXISTS rag_chat_sessions (
    id BIGSERIAL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(20),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    message_count INTEGER DEFAULT 0,
    is_pinned BOOLEAN DEFAULT FALSE
);
COMMENT ON TABLE rag_chat_sessions IS 'RAG聊天会话表';

CREATE INDEX IF NOT EXISTS idx_rag_session_updated ON rag_chat_sessions (updated_at);

CREATE TABLE IF NOT EXISTS rag_chat_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL,
    type VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    message_order INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP,
    completed BOOLEAN DEFAULT TRUE,
    CONSTRAINT fk_rag_chat_messages_session_id FOREIGN KEY (session_id) REFERENCES rag_chat_sessions (id)
);
COMMENT ON TABLE rag_chat_messages IS 'RAG聊天消息表';

CREATE INDEX IF NOT EXISTS idx_rag_message_session ON rag_chat_messages (session_id);
CREATE INDEX IF NOT EXISTS idx_rag_message_order ON rag_chat_messages (session_id, message_order);

CREATE TABLE IF NOT EXISTS rag_session_knowledge_bases (
    session_id BIGINT NOT NULL,
    knowledge_base_id BIGINT NOT NULL,
    PRIMARY KEY (session_id, knowledge_base_id),
    CONSTRAINT fk_rag_session_kb_session_id FOREIGN KEY (session_id) REFERENCES rag_chat_sessions (id),
    CONSTRAINT fk_rag_session_kb_knowledge_base_id FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_bases (id)
);
COMMENT ON TABLE rag_session_knowledge_bases IS 'RAG会话与知识库关联表';

GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;
