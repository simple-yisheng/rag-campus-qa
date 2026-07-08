package com.rag.campus.service;

import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.List;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    /**
     * 上传文档
     * <p>
     * 流程：
     * 1. MD5 哈希去重
     * 2. 保存原始文件到 MinIO
     * 3. 提取文本 → 文档记录写入MySQL（status=PENDING）
     * 4. 发送MQ消息，异步处理分块+向量化
     *
     * @param file     上传的文件
     * @param title    文档标题
     * @param category 分类
     * @return 上传结果
     */
    DocumentUploadResult upload(MultipartFile file, String title, String category, String department);

    /**
     * 查询所有文档
     */
    List<Document> listAll();

    /**
     * 根据ID查询文档
     */
    Document getById(Long id);

    /**
     * 获取文档的原始文件流（从 MinIO 下载）
     *
     * @param documentId 文档ID
     * @return 文件输入流，文档不存在或未存储原始文件时返回 null
     */
    InputStream getFileStream(Long documentId);

    /**
     * 获取文档的 PDF 预览文件流。
     * <p>
     * PDF 文档直接返回原始文件流；Word 文档返回 LibreOffice 生成的 PDF 预览流。
     */
    InputStream getPreviewFileStream(Long documentId);

    /**
     * 删除文档 — 同时删除关联 chunks、MinIO 文件、向量索引
     *
     * @param documentId 文档ID
     */
    void delete(Long documentId);

    /**
     * 处理文档 — 分块 + 向量化 + 写入向量索引
     * <p>
     * 此方法由MQ消费者调用
     *
     * @param documentId 文档ID
     */
    void processDocument(Long documentId);

    /**
     * 获取文档完整文本内容。
     * <p>
     * 优先从 MinIO 原始文件重新提取，保留比分块拼接更完整的文本结构；
     * 老文档缺少原始文件时才回退到数据库原文或 chunks 拼接。
     *
     * @param documentId 文档ID
     * @return 文档完整纯文本，文档不存在或无可用内容时返回 null
     */
    String getDocumentContent(Long documentId);

    /**
     * 获取文档 chunk 列表，按 chunkIndex 升序返回。
     *
     * @param documentId 文档 ID
     * @return chunk 列表，文档不存在或尚无 chunk 时返回空列表
     */
    List<DocumentChunk> getDocumentChunks(Long documentId);
}
