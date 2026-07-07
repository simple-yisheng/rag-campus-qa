-- ============================================================
-- 校园智答 - 数据库初始化脚本
-- ============================================================

CREATE DATABASE IF NOT EXISTS rag_campus DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE rag_campus;

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
    status          VARCHAR(20)   DEFAULT 'PENDING' COMMENT '处理状态: PENDING-待处理 / PROCESSING-处理中 / DONE-已完成 / FAILED-失败',
    chunk_count     INT           DEFAULT 0 COMMENT '分块数量',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '上传时间',
    update_time     DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    INDEX idx_category (category),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档表';

-- 文档分块表：存储每个文档切分后的chunk及其向量
DROP TABLE IF EXISTS tb_document_chunk;
CREATE TABLE tb_document_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id     BIGINT        NOT NULL COMMENT '所属文档ID',
    chunk_index     INT           NOT NULL COMMENT '分块序号（从0开始）',
    chunk_text      TEXT          NOT NULL COMMENT '分块文本内容',
    embedding       LONGTEXT      DEFAULT NULL COMMENT '向量数据（JSON数组格式，如 [0.123, -0.456, ...]）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_document_id (document_id),
    FOREIGN KEY (document_id) REFERENCES tb_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分块表';

-- 对话记录表：持久化存储问答历史
DROP TABLE IF EXISTS tb_conversation;
CREATE TABLE tb_conversation (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(64)   NOT NULL COMMENT '会话ID（UUID）',
    question        TEXT          NOT NULL COMMENT '用户问题',
    answer          TEXT          NOT NULL COMMENT '系统回答',
    sources         TEXT          DEFAULT NULL COMMENT '引用来源（JSON: [{"title":"xx","chunkIndex":0,"score":0.95}]）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    INDEX idx_session_id (session_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话记录表';
