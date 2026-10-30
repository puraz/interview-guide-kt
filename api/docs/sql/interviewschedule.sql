-- =========================
-- 面试日程模块
-- =========================

CREATE TABLE IF NOT EXISTS interview_schedule (
    id BIGSERIAL PRIMARY KEY,
    company_name VARCHAR(255) NOT NULL, -- 公司名称
    "position" VARCHAR(255) NOT NULL, -- 岗位名称
    interview_time TIMESTAMP NOT NULL, -- 面试时间
    interview_type VARCHAR(255), -- 面试类型（现场/电话/视频）
    interviewer VARCHAR(255), -- 面试官
    meeting_link TEXT, -- 会议链接
    notes TEXT, -- 备注
    round_number INTEGER, -- 面试轮次
    status VARCHAR(255) NOT NULL, -- 状态：PENDING/COMPLETED/CANCELLED/RESCHEDULED
    created_at TIMESTAMP, -- 创建时间
    updated_at TIMESTAMP, -- 更新时间
    CONSTRAINT interview_schedule_status_check
        CHECK (status IN ('PENDING', 'COMPLETED', 'CANCELLED', 'RESCHEDULED'))
);
