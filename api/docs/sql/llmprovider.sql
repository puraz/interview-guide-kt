-- =========================
-- LLM Provider 管理模块表结构
-- =========================

-- Provider 配置表（API Key 使用 AES/GCM 加密存储）
CREATE TABLE IF NOT EXISTS llm_provider_config (
    id VARCHAR(64) PRIMARY KEY, -- Provider ID，例如 dashscope
    base_url VARCHAR(512) NOT NULL, -- OpenAI 兼容 API Base URL
    api_key_ciphertext VARCHAR(4096) NOT NULL, -- 加密后的 API Key
    api_key_nonce VARCHAR(64) NOT NULL, -- AES/GCM nonce（Base64）
    model VARCHAR(128) NOT NULL, -- 聊天模型名称
    embedding_model VARCHAR(128), -- Embedding 模型名称
    embedding_dimensions INTEGER, -- Embedding 维度
    supports_embedding BOOLEAN NOT NULL, -- 是否支持 Embedding
    temperature DOUBLE PRECISION, -- 采样温度
    enabled BOOLEAN NOT NULL, -- 是否启用
    builtin BOOLEAN NOT NULL, -- 是否内置 Provider
    created_at TIMESTAMP NOT NULL, -- 创建时间
    updated_at TIMESTAMP NOT NULL -- 更新时间
);

-- 全局 Provider 设置（单行表，id 固定为 1）
CREATE TABLE IF NOT EXISTS llm_global_setting (
    id BIGINT PRIMARY KEY, -- 固定主键
    default_chat_provider_id VARCHAR(64) NOT NULL, -- 默认聊天 Provider
    default_embedding_provider_id VARCHAR(64) NOT NULL, -- 默认 Embedding Provider
    created_at TIMESTAMP NOT NULL, -- 创建时间
    updated_at TIMESTAMP NOT NULL -- 更新时间
);
