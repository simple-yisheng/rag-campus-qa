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
