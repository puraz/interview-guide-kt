-- =========================
-- 语音面试模块表结构
-- =========================

-- 语音面试会话表
CREATE TABLE IF NOT EXISTS voice_interview_sessions (
    id BIGSERIAL PRIMARY KEY,
    user_id VARCHAR(255), -- 用户标识
    resume_id BIGINT, -- 关联简历ID
    role_type VARCHAR(255) NOT NULL, -- 面试角色/方向
    skill_id VARCHAR(64), -- 面试方向 Skill ID
    difficulty VARCHAR(16), -- 难度
    custom_jd_text TEXT, -- 自定义 JD 文本
    planned_duration INTEGER, -- 计划时长（分钟）
    actual_duration INTEGER, -- 实际时长（分钟）
    status VARCHAR(255), -- 会话状态：IN_PROGRESS/PAUSED/COMPLETED/FAILED
    current_phase VARCHAR(255), -- 当前阶段：INTRO/TECH/PROJECT/HR/COMPLETED
    intro_enabled BOOLEAN, -- 是否启用自我介绍环节
    tech_enabled BOOLEAN, -- 是否启用技术环节
    project_enabled BOOLEAN, -- 是否启用项目环节
    hr_enabled BOOLEAN, -- 是否启用 HR 环节
    llm_provider VARCHAR(50), -- 使用的 LLM Provider
    start_time TIMESTAMP, -- 开始时间
    end_time TIMESTAMP, -- 结束时间
    paused_at TIMESTAMP, -- 暂停时间
    resumed_at TIMESTAMP, -- 恢复时间
    evaluate_status VARCHAR(255), -- 评估状态：PENDING/PROCESSING/COMPLETED/FAILED
    evaluate_error VARCHAR(500), -- 评估错误信息
    created_at TIMESTAMP, -- 创建时间
    updated_at TIMESTAMP, -- 更新时间
    CONSTRAINT voice_interview_sessions_status_check
        CHECK (status IN ('IN_PROGRESS', 'PAUSED', 'COMPLETED', 'FAILED')),
    CONSTRAINT voice_interview_sessions_current_phase_check
        CHECK (current_phase IN ('INTRO', 'TECH', 'PROJECT', 'HR', 'COMPLETED')),
    CONSTRAINT voice_interview_sessions_evaluate_status_check
        CHECK (evaluate_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

-- 语音面试消息表（字幕与阶段对话）
CREATE TABLE IF NOT EXISTS voice_interview_messages (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT, -- 关联会话ID
    message_type VARCHAR(255) NOT NULL, -- 消息类型
    phase VARCHAR(255), -- 所属阶段
    sequence_num INTEGER, -- 会话内顺序号
    user_recognized_text TEXT, -- 用户语音识别文本
    ai_generated_text TEXT, -- AI 生成文本
    "timestamp" TIMESTAMP, -- 消息时间
    created_at TIMESTAMP, -- 创建时间
    summary_covered_sequence_num INTEGER, -- 摘要显式覆盖边界（仅 SUMMARY 行使用）
    CONSTRAINT voice_interview_messages_phase_check
        CHECK (phase IN ('INTRO', 'TECH', 'PROJECT', 'HR', 'COMPLETED'))
);

CREATE INDEX IF NOT EXISTS idx_voice_messages_session_seq
    ON voice_interview_messages (session_id, sequence_num);

-- 语音面试评估表
CREATE TABLE IF NOT EXISTS voice_interview_evaluations (
    id BIGSERIAL PRIMARY KEY,
    session_id BIGINT UNIQUE, -- 关联会话ID（一对一）
    interviewer_role VARCHAR(255), -- 面试官角色
    interview_date TIMESTAMP, -- 面试日期
    overall_score INTEGER, -- 总分
    overall_feedback TEXT, -- 总体评价
    strengths_json TEXT, -- 优势JSON
    improvements_json TEXT, -- 改进建议JSON
    question_evaluations_json TEXT, -- 逐题评估JSON
    reference_answers_json TEXT, -- 参考答案JSON
    created_at TIMESTAMP -- 创建时间
);
