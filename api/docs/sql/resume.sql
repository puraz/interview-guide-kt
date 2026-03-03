-- 简历模块表结构

CREATE TABLE IF NOT EXISTS resumes (
    id BIGSERIAL PRIMARY KEY,
    file_hash VARCHAR(64) NOT NULL UNIQUE, -- 文件内容哈希
    original_filename VARCHAR(255) NOT NULL, -- 原始文件名
    file_size BIGINT, -- 文件大小（字节）
    content_type VARCHAR(255), -- 文件类型
    storage_key VARCHAR(500), -- 存储文件Key
    storage_url VARCHAR(1000), -- 存储文件URL
    resume_text TEXT, -- 解析后的简历文本
    uploaded_at TIMESTAMP NOT NULL, -- 上传时间
    last_accessed_at TIMESTAMP, -- 最后访问时间
    access_count INTEGER, -- 访问次数
    analyze_status VARCHAR(20), -- 分析状态
    analyze_error VARCHAR(500) -- 分析错误信息
);

CREATE INDEX IF NOT EXISTS idx_resume_hash ON resumes (file_hash);

CREATE TABLE IF NOT EXISTS resume_analyses (
    id BIGSERIAL PRIMARY KEY,
    resume_id BIGINT NOT NULL, -- 关联简历ID
    overall_score INTEGER, -- 总分
    content_score INTEGER, -- 内容评分
    structure_score INTEGER, -- 结构评分
    skill_match_score INTEGER, -- 技能匹配评分
    expression_score INTEGER, -- 表达评分
    project_score INTEGER, -- 项目评分
    summary TEXT, -- 简历摘要
    strengths_json TEXT, -- 优点列表JSON
    suggestions_json TEXT, -- 建议列表JSON
    analyzed_at TIMESTAMP NOT NULL, -- 评测时间
    CONSTRAINT fk_resume_analysis_resume FOREIGN KEY (resume_id) REFERENCES resumes(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_resume_analysis_resume ON resume_analyses (resume_id, analyzed_at DESC);
