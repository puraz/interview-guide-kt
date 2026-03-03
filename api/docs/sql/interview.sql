-- 面试模块表结构

CREATE TABLE IF NOT EXISTS interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL UNIQUE, -- 会话ID
    resume_id BIGINT NOT NULL, -- 关联简历ID
    total_questions INTEGER, -- 总题数
    current_question_index INTEGER, -- 当前题索引
    status VARCHAR(20), -- 会话状态
    questions_json TEXT, -- 问题列表JSON
    overall_score INTEGER, -- 总分
    overall_feedback TEXT, -- 总体评价
    strengths_json TEXT, -- 优势JSON
    improvements_json TEXT, -- 改进建议JSON
    reference_answers_json TEXT, -- 参考答案JSON
    created_at TIMESTAMP NOT NULL, -- 创建时间
    completed_at TIMESTAMP, -- 完成时间
    evaluate_status VARCHAR(20), -- 评估状态
    evaluate_error VARCHAR(500), -- 评估错误信息
    CONSTRAINT fk_interview_session_resume FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_interview_session_resume_created ON interview_sessions (resume_id, created_at);
CREATE INDEX IF NOT EXISTS idx_interview_session_resume_status_created ON interview_sessions (resume_id, status, created_at);

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
