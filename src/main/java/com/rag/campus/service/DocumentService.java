package com.rag.campus.service;

import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理服务接口
 */
public interface DocumentService {

    /**
     * 上传文档
     * <p>
     * 流程：
     * 1. 保存文件 → 提取文本
     * 2. 文档记录写入MySQL（status=PENDING）
     * 3. 发送MQ消息，异步处理分块+向量化
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
     * 处理文档 — 分块 + 向量化 + 写入向量索引
     * <p>
     * 此方法由MQ消费者调用
     *
     * @param documentId 文档ID
     */
    void processDocument(Long documentId);
}
