package com.rag.campus.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.campus.client.EmbeddingClient;
import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.DocumentChunkMapper;
import com.rag.campus.mapper.DocumentMapper;
import com.rag.campus.service.DocumentService;
import com.rag.campus.support.DocumentChunker;
import com.rag.campus.support.DocumentConverter;
import com.rag.campus.support.MinioStorageService;
import com.rag.campus.support.VectorStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理服务实现
 */
@Slf4j
@Service
public class DocumentServiceImpl implements DocumentService {

    private final DocumentMapper documentMapper;
    private final DocumentChunkMapper chunkMapper;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final RabbitTemplate rabbitTemplate;
    private final List<DocumentConverter> converters;
    private final MinioStorageService minioStorage;

    /** 扩展名 → 转换器映射（启动时构建） */
    private final Map<String, DocumentConverter> converterMap = new HashMap<>();

    @Value("${rag.chunk.size:512}")
    private int chunkSize;

    @Value("${rag.chunk.overlap:128}")
    private int chunkOverlap;

    /** MQ路由常量 */
    public static final String DOCUMENT_PROCESS_EXCHANGE = "rag.document.exchange";
    public static final String DOCUMENT_PROCESS_ROUTING_KEY = "rag.document.process";

    public DocumentServiceImpl(DocumentMapper documentMapper,
                               DocumentChunkMapper chunkMapper,
                               EmbeddingClient embeddingClient,
                               VectorStore vectorStore,
                               RabbitTemplate rabbitTemplate,
                               List<DocumentConverter> converters,
                               MinioStorageService minioStorage) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.rabbitTemplate = rabbitTemplate;
        this.converters = converters;
        this.minioStorage = minioStorage;
    }

    /** 构建扩展名 → 转换器映射 */
    @PostConstruct
    public void initConverterMap() {
        for (DocumentConverter converter : converters) {
            for (String ext : converter.supportedExtensions()) {
                converterMap.put(ext, converter);
            }
        }
        log.info("文档转换器注册完成: {}", converterMap.keySet());
    }

    @Override
    public DocumentUploadResult upload(MultipartFile file, String title, String category, String department) {
        String originalName = file.getOriginalFilename();

        // 1. 计算文件 MD5 哈希
        byte[] fileBytes;
        String md5;
        try {
            fileBytes = file.getBytes();
            md5 = DigestUtil.md5Hex(fileBytes);
        } catch (IOException e) {
            log.error("文件读取失败", e);
            return DocumentUploadResult.builder()
                    .status("FAILED")
                    .message("文件读取失败: " + e.getMessage())
                    .build();
        }

        // 2. MD5 去重检查
        Document existing = documentMapper.selectOne(
                new LambdaQueryWrapper<Document>()
                        .eq(Document::getContentHash, md5)
                        .eq(Document::getStatus, "DONE")
        );
        if (existing != null) {
            log.info("重复文件上传被拦截: md5={}, 已存在文档id={}, title={}", md5, existing.getId(), existing.getTitle());
            return DocumentUploadResult.builder()
                    .documentId(existing.getId())
                    .title(existing.getTitle())
                    .status("DUPLICATE")
                    .message("该文件已上传过，文档标题：《" + existing.getTitle() + "》，无需重复上传")
                    .build();
        }

        // 3. 提取文本内容（通过转换器）
        String text;
        try {
            text = extractText(fileBytes, originalName);
        } catch (IOException e) {
            log.error("文本提取失败", e);
            return DocumentUploadResult.builder()
                    .status("FAILED")
                    .message("文件读取失败: " + e.getMessage())
                    .build();
        }

        if (StrUtil.isBlank(text)) {
            return DocumentUploadResult.builder()
                    .status("FAILED")
                    .message("文件内容为空，无法处理")
                    .build();
        }

        // 4. 保存文档记录（先插入，获取自增ID）
        Document doc = new Document();
        doc.setTitle(StrUtil.isBlank(title) ? originalName : title);
        doc.setCategory(category);
        doc.setDepartment(StrUtil.isBlank(department) ? "" : department);
        doc.setContent(text);
        doc.setFileType(getFileType(originalName));
        doc.setContentHash(md5);
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setCreateTime(LocalDateTime.now());
        documentMapper.insert(doc);

        // 5. 保存原始文件到 MinIO
        try {
            String contentType = file.getContentType();
            if (contentType == null) contentType = "application/octet-stream";
            String fileKey = minioStorage.upload(doc.getId(), originalName, fileBytes, contentType);
            doc.setFileKey(fileKey);
            documentMapper.updateById(doc);
        } catch (Exception e) {
            log.error("MinIO存储失败，文档仍可正常检索: id={}", doc.getId(), e);
            // MinIO 失败不影响检索流程，文档仍可正常分块
        }

        // 6. 发送MQ消息，异步处理
        rabbitTemplate.convertAndSend(DOCUMENT_PROCESS_EXCHANGE, DOCUMENT_PROCESS_ROUTING_KEY, doc.getId());

        log.info("文档上传成功: id={}, title={}, md5={}, 字符数={}", doc.getId(), doc.getTitle(), md5, text.length());
        return DocumentUploadResult.builder()
                .documentId(doc.getId())
                .title(doc.getTitle())
                .status("PENDING")
                .message("文档上传成功，正在后台处理中，请稍候...")
                .build();
    }

    @Override
    public List<Document> listAll() {
        return documentMapper.selectList(
                new LambdaQueryWrapper<Document>()
                        .orderByDesc(Document::getCreateTime)
        );
    }

    @Override
    public Document getById(Long id) {
        return documentMapper.selectById(id);
    }

    @Override
    public InputStream getFileStream(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null || StrUtil.isBlank(doc.getFileKey())) {
            return null;
        }
        return minioStorage.download(doc.getFileKey());
    }

    @Override
    @Transactional
    public void delete(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) return;

        // 删除 MinIO 文件
        if (StrUtil.isNotBlank(doc.getFileKey())) {
            minioStorage.delete(doc.getFileKey());
        }

        // 删除向量索引
        vectorStore.removeByDocumentId(documentId);

        // DB 删除（chunks 通过 CASCADE 自动删除）
        documentMapper.deleteById(documentId);
        log.info("文档已删除: id={}, title={}", documentId, doc.getTitle());
    }

    @Override
    @Transactional
    public void processDocument(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            log.error("文档不存在: id={}", documentId);
            return;
        }

        try {
            doc.setStatus("PROCESSING");
            documentMapper.updateById(doc);

            // === 第一步：分块（按文档分类+内容特征选择策略） ===
            DocumentChunker chunker = new DocumentChunker(chunkSize, chunkOverlap);
            List<String> chunkTexts = chunker.chunk(doc.getContent(), doc.getCategory());
            log.info("文档[id={}] 分块完成: {} 个chunk", documentId, chunkTexts.size());

            if (chunkTexts.isEmpty()) {
                doc.setStatus("FAILED");
                documentMapper.updateById(doc);
                return;
            }

            // === 第二步：批量向量化 ===
            List<float[]> embeddings = embeddingClient.embedBatch(chunkTexts);
            if (embeddings.size() != chunkTexts.size()) {
                log.error("向量化结果数量不匹配: texts={}, embeddings={}", chunkTexts.size(), embeddings.size());
                doc.setStatus("FAILED");
                documentMapper.updateById(doc);
                return;
            }

            // === 第三步：保存chunk + 向量到DB ===
            List<DocumentChunk> chunks = new ArrayList<>();
            List<Long> chunkIds = new ArrayList<>();
            for (int i = 0; i < chunkTexts.size(); i++) {
                DocumentChunk chunk = new DocumentChunk();
                chunk.setDocumentId(documentId);
                chunk.setChunkIndex(i);
                chunk.setChunkText(chunkTexts.get(i));
                chunk.setEmbedding(JSONUtil.toJsonStr(embeddings.get(i)));
                chunk.setCreateTime(LocalDateTime.now());
                chunkMapper.insert(chunk);

                chunks.add(chunk);
                chunkIds.add(chunk.getId());
            }

            // === 第四步：加载到内存向量索引 ===
            vectorStore.addBatch(chunkIds, embeddings, chunks);

            // === 第五步：清理原文（已分块存到 tb_document_chunk，原文不再需要） ===
            doc.setContent("");  // 避免大文本撑大表空间、拖慢列表查询

            // === 第六步：更新文档状态 ===
            doc.setStatus("DONE");
            doc.setChunkCount(chunks.size());
            doc.setUpdateTime(LocalDateTime.now());
            documentMapper.updateById(doc);

            log.info("文档处理完成: id={}, chunks={}", documentId, chunks.size());

        } catch (Exception e) {
            log.error("处理文档失败: id={}", documentId, e);
            doc.setStatus("FAILED");
            documentMapper.updateById(doc);
        }
    }

    // ==================== 私有方法 ====================

    /**
     * 从上传文件中提取文本（通过 DocumentConverter 策略分发）
     */
    private String extractText(byte[] fileBytes, String filename) throws IOException {
        if (filename == null) {
            throw new IOException("文件名为空");
        }

        String ext = filename.toLowerCase();
        // 提取扩展名（含点，如 ".pdf"）
        int dotIndex = ext.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new IOException("无法识别文件类型，请上传 .txt / .md / .pdf / .docx / .doc 格式的文件");
        }
        ext = ext.substring(dotIndex);

        DocumentConverter converter = converterMap.get(ext);
        if (converter == null) {
            throw new IOException("不支持的文件格式: " + ext + "，当前支持: " + converterMap.keySet());
        }

        return converter.convert(fileBytes, filename);
    }

    private String getFileType(String filename) {
        if (filename == null) return "UNKNOWN";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".txt")) return "TXT";
        return "UNKNOWN";
    }
}
