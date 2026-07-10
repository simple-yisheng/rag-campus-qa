-- ============================================================
-- 校园智答 - 数据库初始化脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS rag_campus DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rag_campus;

-- 用户表：存储系统用户信息
DROP TABLE IF EXISTS tb_user;
CREATE TABLE tb_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50)   NOT NULL COMMENT '用户名',
    password        VARCHAR(200)  NOT NULL COMMENT '密码（BCrypt加密）',
    role            VARCHAR(20)   NOT NULL DEFAULT 'USER' COMMENT '角色: USER-普通用户 / ADMIN-管理员',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常 / DISABLED-已禁用',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 文档表：存储上传的校园规章制度、政策文件等
DROP TABLE IF EXISTS tb_document;
CREATE TABLE tb_document (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    title           VARCHAR(255)  NOT NULL COMMENT '文档标题',
    category        VARCHAR(50)   NOT NULL COMMENT '分类: POLICY-规章制度 / SCHOLARSHIP-评奖评优 / ACADEMIC-学业政策 / GUIDE-生活指南 / OTHER-其他',
    department      VARCHAR(128)  DEFAULT '' COMMENT '发布单位/学院',
    content         LONGTEXT      NOT NULL COMMENT '文档原始文本内容',
    file_type       VARCHAR(20)   DEFAULT 'TXT' COMMENT '源文件类型: TXT/PDF/DOCX',
    content_hash    VARCHAR(32)   DEFAULT NULL COMMENT '文件内容MD5哈希，用于去重',
    file_key        VARCHAR(500)  DEFAULT NULL COMMENT 'MinIO存储路径（原始文件）',
    preview_file_key VARCHAR(500) DEFAULT NULL COMMENT 'MinIO存储路径（PDF预览文件）',
    status          VARCHAR(20)   DEFAULT 'PENDING' COMMENT '处理状态: PENDING-待处理 / PROCESSING-处理中 / DONE-已完成 / FAILED-失败',
    chunk_count     INT           DEFAULT 0 COMMENT '分块数量',
    uploader_id     BIGINT        DEFAULT NULL COMMENT '上传者用户ID',
    review_status   VARCHAR(20)   DEFAULT 'PENDING' COMMENT '审核状态: PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回',
    reviewer_id     BIGINT        DEFAULT NULL COMMENT '审核人用户ID',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category (category),
    INDEX idx_status (status),
    INDEX idx_uploader_id (uploader_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- 文档分块表：存储每个文档切分后的chunk及其向量
DROP TABLE IF EXISTS tb_document_chunk;
CREATE TABLE tb_document_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT        NOT NULL COMMENT '所属文档ID',
    chunk_index     INT           NOT NULL COMMENT '分块序号（从0开始）',
    chunk_text      TEXT          NOT NULL COMMENT '分块文本内容',
    page_start      INT           DEFAULT NULL COMMENT 'PDF起始页码（1-based）',
    page_end        INT           DEFAULT NULL COMMENT 'PDF结束页码（1-based）',
    embedding       LONGTEXT      DEFAULT NULL COMMENT '向量数据（JSON数组格式，如 [0.123, -0.456, ...]）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_document_id (document_id),
    FOREIGN KEY (document_id) REFERENCES tb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块表';

-- 会话表：每个 session 是用户的一次对话窗口
DROP TABLE IF EXISTS tb_conversation_session;
CREATE TABLE tb_conversation_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(16)   NOT NULL COMMENT '会话短ID（如 a3b2k9m1）',
    user_id          BIGINT        DEFAULT NULL COMMENT '用户ID（关联 tb_user.id）',
    title            VARCHAR(255)  DEFAULT '新对话' COMMENT '会话标题（首条问题或用户自定义）',
    create_time      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    last_active_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    UNIQUE INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

-- 对话消息表：每轮 Q&A 一行
DROP TABLE IF EXISTS tb_conversation_message;
CREATE TABLE tb_conversation_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(16)   NOT NULL COMMENT '会话短ID（关联 tb_conversation_session.session_id）',
    question        TEXT          NOT NULL COMMENT '用户问题',
    answer          TEXT          NOT NULL COMMENT '系统回答',
    sources         TEXT          DEFAULT NULL COMMENT '引用来源（JSON）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    INDEX idx_session_id (session_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';
