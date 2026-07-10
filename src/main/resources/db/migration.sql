-- ============================================================
-- 校园智答 - 数据库迁移脚本（已有数据库升级用）
-- 新增字段：content_hash（MD5去重）、file_key（MinIO文件路径）
-- 执行方式: cmd /c "docker exec -i rag-mysql mysql -uroot -proot rag_campus < src\main\resources\db\migration.sql"
-- ============================================================

ALTER TABLE tb_document ADD COLUMN content_hash VARCHAR(32) DEFAULT NULL COMMENT '文件内容MD5哈希，用于去重';
ALTER TABLE tb_document ADD COLUMN file_key VARCHAR(500) DEFAULT NULL COMMENT 'MinIO存储路径（原始文件）';
ALTER TABLE tb_document ADD COLUMN preview_file_key VARCHAR(500) DEFAULT NULL COMMENT 'MinIO存储路径（PDF预览文件）';
ALTER TABLE tb_document ADD INDEX idx_content_hash (content_hash);

ALTER TABLE tb_document_chunk ADD COLUMN page_start INT DEFAULT NULL COMMENT 'PDF起始页码（1-based）';
ALTER TABLE tb_document_chunk ADD COLUMN page_end INT DEFAULT NULL COMMENT 'PDF结束页码（1-based）';

-- ============================================================
-- v2: 用户认证与角色权限
-- ============================================================

CREATE TABLE IF NOT EXISTS tb_user (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(50)   NOT NULL COMMENT '用户名',
    password        VARCHAR(200)  NOT NULL COMMENT '密码（BCrypt加密）',
    role            VARCHAR(20)   NOT NULL DEFAULT 'USER' COMMENT '角色: USER-普通用户 / ADMIN-管理员',
    status          VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE' COMMENT '状态: ACTIVE-正常 / DISABLED-已禁用',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE INDEX idx_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

ALTER TABLE tb_document ADD COLUMN uploader_id BIGINT DEFAULT NULL COMMENT '上传者用户ID';
ALTER TABLE tb_document ADD COLUMN review_status VARCHAR(20) DEFAULT 'APPROVED' COMMENT '审核状态: PENDING-待审核 / APPROVED-已通过 / REJECTED-已驳回';
ALTER TABLE tb_document ADD INDEX idx_uploader_id (uploader_id);

ALTER TABLE tb_conversation ADD COLUMN user_id BIGINT DEFAULT NULL COMMENT '用户ID（关联 tb_user.id）';
ALTER TABLE tb_conversation ADD INDEX idx_user_id (user_id);

-- ============================================================
-- v3: 拆分对话表为 session + message
-- ============================================================

-- 1. 创建新表
CREATE TABLE IF NOT EXISTS tb_conversation_session (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id       VARCHAR(16)   NOT NULL COMMENT '会话短ID',
    user_id          BIGINT        DEFAULT NULL COMMENT '用户ID',
    title            VARCHAR(255)  DEFAULT '新对话' COMMENT '会话标题',
    create_time      DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    last_active_time DATETIME      DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    UNIQUE INDEX idx_session_id (session_id),
    INDEX idx_user_id (user_id),
    INDEX idx_last_active (last_active_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话表';

CREATE TABLE IF NOT EXISTS tb_conversation_message (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id      VARCHAR(16)   NOT NULL COMMENT '会话短ID',
    question        TEXT          NOT NULL COMMENT '用户问题',
    answer          TEXT          NOT NULL COMMENT '系统回答',
    sources         TEXT          DEFAULT NULL COMMENT '引用来源（JSON）',
    create_time     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '提问时间',
    INDEX idx_session_id (session_id),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对话消息表';

-- 2. 迁移旧数据（如果 tb_conversation 存在）
--    将旧表数据按 session_id 分组：首条 question 做标题，所有 Q&A 做消息
INSERT IGNORE INTO tb_conversation_session (session_id, user_id, title, create_time, last_active_time)
SELECT c.session_id,
       c.user_id,
       (SELECT c2.question FROM tb_conversation c2
        WHERE c2.session_id = c.session_id ORDER BY c2.create_time ASC LIMIT 1) AS title,
       MIN(c.create_time) AS create_time,
       MAX(c.create_time) AS last_active_time
FROM tb_conversation c
GROUP BY c.session_id, c.user_id;

INSERT IGNORE INTO tb_conversation_message (session_id, question, answer, sources, create_time)
SELECT session_id, question, answer, sources, create_time
FROM tb_conversation;

-- 3. 删除旧表（确认迁移无误后执行；首次迁移可先注释掉，验证后再手动删）
-- DROP TABLE IF EXISTS tb_conversation;

-- ============================================================
-- v4: 文档审核人字段
-- ============================================================

ALTER TABLE tb_document ADD COLUMN reviewer_id BIGINT DEFAULT NULL COMMENT '审核人用户ID';

-- 将已有 APPROVED 状态的文档的默认审核人设为上传者（仅作为兜底，实际审核应由管理员操作）
-- UPDATE tb_document SET reviewer_id = uploader_id WHERE review_status = 'APPROVED' AND reviewer_id IS NULL;

-- 修正默认值：新上传的文档默认审核状态应为 PENDING（已有数据的默认值仍为 APPROVED）
-- 如果你希望已有文档保持可检索，不执行下面这条：
-- ALTER TABLE tb_document ALTER COLUMN review_status SET DEFAULT 'PENDING';
