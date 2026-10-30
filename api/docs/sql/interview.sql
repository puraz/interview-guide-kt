-- =========================
-- 面试模块表结构（文字面试）
-- =========================

CREATE TABLE IF NOT EXISTS interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL UNIQUE, -- 会话ID
    resume_id BIGINT, -- 关联简历ID（知识库面试可为空）
    total_questions INTEGER, -- 总题数
    current_question_index INTEGER, -- 当前题索引
    status VARCHAR(20), -- 会话状态：CREATED/IN_PROGRESS/COMPLETED/EVALUATED
    questions_json TEXT, -- 问题列表JSON
    overall_score INTEGER, -- 总分
    overall_feedback TEXT, -- 总体评价
    strengths_json TEXT, -- 优势JSON
    improvements_json TEXT, -- 改进建议JSON
    reference_answers_json TEXT, -- 参考答案JSON
    created_at TIMESTAMP NOT NULL, -- 创建时间
    completed_at TIMESTAMP, -- 完成时间
    evaluate_status VARCHAR(20), -- 评估状态：PENDING/PROCESSING/COMPLETED/FAILED
    evaluate_error VARCHAR(500), -- 评估错误信息
    difficulty VARCHAR(16), -- 难度：JUNIOR/MID/SENIOR
    interview_category VARCHAR(64), -- 知识库面试方向（普通面试为 NULL）
    knowledge_base_id BIGINT, -- 知识库面试关联的知识库ID
    llm_provider VARCHAR(50), -- 使用的 LLM Provider
    skill_id VARCHAR(64), -- 面试方向 Skill ID
    source_type VARCHAR(32), -- 会话来源：RESUME/SKILL/KNOWLEDGE_BASE
    request_id VARCHAR(64), -- 创建请求幂等ID
    CONSTRAINT fk_interview_session_resume FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_interview_session_resume_created ON interview_sessions (resume_id, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_resume_status_created ON interview_sessions (resume_id, status, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_skill_created ON interview_sessions (skill_id, created_at);
CREATE UNIQUE INDEX IF NOT EXISTS uk_interview_sessions_request_id ON interview_sessions (request_id);

CREATE TABLE IF NOT EXISTS interview_answers (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT NOT NULL, -- 关联会话ID
    question_index INTEGER, -- 问题索引
    question TEXT, -- 问题内容
    category VARCHAR(255), -- 问题类别
    user_answer TEXT, -- 用户答案
    score INTEGER, -- 得分
    feedback TEXT, -- 反馈
    reference_answer TEXT, -- 参考答案
    key_points_json TEXT, -- 关键点JSON
    answered_at TIMESTAMP NOT NULL, -- 回答时间
    CONSTRAINT fk_interview_answer_session FOREIGN KEY (session_id) REFERENCES interview_sessions(id) ON DELETE CASCADE,
    CONSTRAINT uk_interview_answer_session_question UNIQUE (session_id, question_index)
);

CREATE INDEX IF NOT EXISTS idx_interview_answer_session_question ON interview_answers (session_id, question_index);
