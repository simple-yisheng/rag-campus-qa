-- H2 内存数据库初始化 DDL（与 MySQL 表结构对齐）
-- H2 兼容模式: MODE=MySQL

CREATE TABLE IF NOT EXISTS tb_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password VARCHAR(256) NOT NULL,
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_document (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(256) NOT NULL,
    category VARCHAR(64),
    content_hash VARCHAR(64),
    file_key VARCHAR(256),
    file_type VARCHAR(32),
    uploader_id BIGINT,
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_document_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_text TEXT NOT NULL,
    chunk_index INT NOT NULL,
    page_start INT,
    page_end INT,
    embedding TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_conversation_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(16) NOT NULL,
    user_id BIGINT,
    title VARCHAR(256),
    last_active_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS tb_conversation_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(16),
    question TEXT,
    answer TEXT,
    sources TEXT,
    create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
