-- =============================================
-- 人生沙盘数据库建表语句 - 基础表
-- MySQL 8.x 版本
-- =============================================

-- 创建数据库
CREATE DATABASE IF NOT EXISTS life_sandbox 
CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

USE life_sandbox;

-- =============================================
-- 1. 用户基础表
-- =============================================

-- 用户表
CREATE TABLE users (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '用户ID',
    user_number CHAR(7) NOT NULL COMMENT '用户编号（7位纯数字，首位非0）',
    platform_type TINYINT NOT NULL COMMENT '用户平台类型：1-微信小程序，2-抖音小程序，3-H5',
    openid VARCHAR(64) DEFAULT NULL COMMENT '平台开放openid',
    unionid VARCHAR(64) DEFAULT NULL COMMENT '跨平台统一id',
    wx_jssdk_openid VARCHAR(64) DEFAULT NULL COMMENT '微信网页（JSSDK）openid',
    wx_mini_openid  VARCHAR(64) DEFAULT NULL COMMENT '微信小程序openid',
    wx_unionid      VARCHAR(64) DEFAULT NULL COMMENT '微信unionid',
    dy_jssdk_openid VARCHAR(64) DEFAULT NULL COMMENT '抖音网页(JSSDK) openid',
    dy_mini_openid  VARCHAR(64) DEFAULT NULL COMMENT '抖音小程序openid',
    dy_unionid      VARCHAR(64) DEFAULT NULL COMMENT '抖音unionid',
    parent_id     BIGINT DEFAULT NULL COMMENT '邀请人用户ID',
    nickname VARCHAR(50) DEFAULT NULL COMMENT '用户昵称',
    avatar_url VARCHAR(255) DEFAULT NULL COMMENT '头像URL',
    phone VARCHAR(20) not null COMMENT '手机号',
    gender TINYINT NOT NULL DEFAULT 0 COMMENT '性别：0-未知，1-男，2-女',
    coin_balance INT NOT NULL DEFAULT 0 COMMENT '金币余额',
    total_recharge DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '累计充值金额',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-正常，2-冻结',
    frozen_at DATETIME NULL COMMENT '冻结时间',
    last_login_at DATETIME NULL COMMENT '最后登录时间',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    ext_data JSON DEFAULT NULL COMMENT '扩展数据',
    
    UNIQUE KEY uk_phone (phone),
    UNIQUE KEY uk_user_number (user_number),
    UNIQUE KEY uk_wx_jssdk_openid (wx_jssdk_openid),
    UNIQUE KEY uk_wx_mini_openid (wx_mini_openid),
    UNIQUE KEY uk_wx_unionid (wx_unionid),
    UNIQUE KEY uk_dy_jssdk_openid (dy_jssdk_openid),
    UNIQUE KEY uk_dy_mini_openid (dy_mini_openid),
    UNIQUE KEY uk_dy_unionid (dy_unionid),
    CONSTRAINT chk_user_number CHECK (user_number REGEXP '^[1-9][0-9]{6}$'),
    INDEX idx_status_login (status, last_login_at),
    INDEX idx_created_at (created_at),
    INDEX idx_parent_id (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户基础表';

-- 用户档案表
CREATE TABLE user_profiles (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '档案ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    age TINYINT DEFAULT NULL COMMENT '年龄',
    birthday DATE DEFAULT NULL COMMENT '生日',
    birth_time TIME DEFAULT NULL COMMENT '出生具体时间（时:分）',
    zodiac_sign VARCHAR(20) DEFAULT NULL COMMENT '星座（基于生日自动计算）',
    chinese_zodiac VARCHAR(10) DEFAULT NULL COMMENT '生肖（基于出生年份自动计算）',
    birth_place_type TINYINT DEFAULT NULL COMMENT '出生地类型：1-城市，2-农村',
    school VARCHAR(100) DEFAULT NULL COMMENT '学校',
    major VARCHAR(100) DEFAULT NULL COMMENT '专业',
    occupation VARCHAR(50) DEFAULT NULL COMMENT '职业',
    personal_intro TEXT DEFAULT NULL COMMENT '个人介绍',
    education_level TINYINT DEFAULT NULL COMMENT '教育程度：1-初中，2-高中，3-大专，4-本科，5-硕士，6-博士',
    income_range TINYINT DEFAULT NULL COMMENT '收入范围：1-3k以下，2-3-5k，3-5-8k，4-8-12k，5-12-20k，6-20k以上',
    family_status TINYINT DEFAULT NULL COMMENT '家庭状况：1-单身，2-恋爱，3-已婚无子女，4-已婚有子女，5-离异',
    city VARCHAR(50) DEFAULT NULL COMMENT '所在城市',
    interests JSON DEFAULT NULL COMMENT '兴趣标签数组',
    personality_tags JSON DEFAULT NULL COMMENT '性格标签',
    risk_preference TINYINT DEFAULT NULL COMMENT '风险偏好：1-保守，2-中性，3-激进',
    profile_completeness TINYINT NOT NULL DEFAULT 0 COMMENT '档案完整度百分比',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    
    UNIQUE KEY uk_user_id (user_id),
    INDEX idx_age_occupation (age, occupation),
    INDEX idx_city (city),
    INDEX idx_zodiac (zodiac_sign),
    INDEX idx_chinese_zodiac (chinese_zodiac)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户档案表';

-- 用户标签表
CREATE TABLE user_tags (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '标签ID',
    tag_name VARCHAR(50) NOT NULL COMMENT '标签名称',
    tag_type TINYINT NOT NULL COMMENT '标签类型：1-系统标签，2-手动标签',
    description VARCHAR(200) DEFAULT NULL COMMENT '标签描述',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0-禁用，1-启用',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
        updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_tag_name (tag_name),
    INDEX idx_type_status (tag_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签表';

-- 用户标签关联表
CREATE TABLE user_tag_relations (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '关联ID',
    user_id BIGINT NOT NULL COMMENT '用户ID',
    tag_id BIGINT NOT NULL COMMENT '标签ID',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_tag (user_id, tag_id),
    INDEX idx_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户标签关联表';

-- 用户小程序码表
CREATE TABLE user_mini_program_codes
(
    id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
    user_id       BIGINT       NOT NULL COMMENT '所属用户ID',
    platform_type TINYINT      NOT NULL COMMENT '平台类型：1-微信小程序，2-抖音小程序，3-H5',
    code_type     TINYINT      NOT NULL COMMENT '二维码类型：1-邀请海报，2-通用分享',
    scene         VARCHAR(64)  NOT NULL COMMENT '小程序码scene参数',
    page          VARCHAR(120) NOT NULL COMMENT '跳转页面路径',
    env_version   VARCHAR(20)  NOT NULL DEFAULT 'release' COMMENT '小程序版本：release/trial/develop',
    qiniu_key     VARCHAR(255) NOT NULL COMMENT '七牛云文件Key',
    code_url      VARCHAR(512) NOT NULL COMMENT '二维码访问URL',
    width         INT          NOT NULL DEFAULT 430 COMMENT '二维码宽度',
    is_hyaline    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否透明底色：0-否，1-是',
    auto_color    TINYINT      NOT NULL DEFAULT 1 COMMENT '是否自动配置颜色：0-否，1-是',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：0-失效，1-有效',
    generated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '二维码生成时间',
    last_used_at  DATETIME              DEFAULT NULL COMMENT '最近一次使用时间',
    extra_json    JSON                  DEFAULT NULL COMMENT '扩展参数',
    created_at    DATETIME              DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at    DATETIME              DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_user_platform_scene (user_id, platform_type, scene),
    KEY idx_user_platform (user_id, platform_type),
    KEY idx_platform_status (platform_type, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='用户小程序码缓存表';

-- =============================================
-- 既有库升级脚本（将平台字段拆分并兼容 H5 登录）
-- =============================================

-- 1. 为现有 users 表增加多端 openid / unionid 字段（如已存在请跳过）
ALTER TABLE users
    ADD COLUMN wx_jssdk_openid VARCHAR(64) NULL COMMENT '微信网页（JSSDK）openid' AFTER unionid,
    ADD COLUMN wx_mini_openid  VARCHAR(64) NULL COMMENT '微信小程序openid' AFTER wx_jssdk_openid,
    ADD COLUMN wx_unionid      VARCHAR(64) NULL COMMENT '微信unionid' AFTER wx_mini_openid,
    ADD COLUMN dy_jssdk_openid VARCHAR(64) NULL COMMENT '抖音网页(JSSDK) openid' AFTER wx_unionid,
    ADD COLUMN dy_mini_openid  VARCHAR(64) NULL COMMENT '抖音小程序openid' AFTER dy_jssdk_openid,
    ADD COLUMN dy_unionid      VARCHAR(64) NULL COMMENT '抖音unionid' AFTER dy_mini_openid;

-- 2. 将原有 openid/unionid 数据回填到具体平台字段
UPDATE users
SET wx_mini_openid = CASE WHEN platform_type = 1 THEN openid ELSE wx_mini_openid END,
    wx_unionid     = CASE WHEN platform_type = 1 THEN unionid ELSE wx_unionid END,
    dy_mini_openid = CASE WHEN platform_type = 2 THEN openid ELSE dy_mini_openid END,
    dy_unionid     = CASE WHEN platform_type = 2 THEN unionid ELSE dy_unionid END;

-- 如果后续新增 H5（抖音网页）授权，可在登录成功后写入 dy_jssdk_openid、dy_unionid。

-- 3. 根据字段建立唯一索引，确保跨平台账号唯一（已存在则跳过）
ALTER TABLE users
    ADD UNIQUE INDEX uk_wx_jssdk_openid (wx_jssdk_openid),
    ADD UNIQUE INDEX uk_wx_mini_openid (wx_mini_openid),
    ADD UNIQUE INDEX uk_wx_unionid (wx_unionid),
    ADD UNIQUE INDEX uk_dy_jssdk_openid (dy_jssdk_openid),
    ADD UNIQUE INDEX uk_dy_mini_openid (dy_mini_openid),
    ADD UNIQUE INDEX uk_dy_unionid (dy_unionid);

-- 4. 若存在相同 unionid 的账号，请先人工确认合并或保留策略，再执行唯一索引创建。
