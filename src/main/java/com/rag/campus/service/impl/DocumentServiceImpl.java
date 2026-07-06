package com.rag.campus.service.impl;

import cn.hutool.core.util.StrUtil;
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
import com.rag.campus.support.VectorStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
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
                               List<DocumentConverter> converters) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.rabbitTemplate = rabbitTemplate;
        this.converters = converters;
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
        // 1. 提取文本内容（通过转换器）
        String text;
        try {
            text = extractText(file);
        } catch (IOException e) {
            log.error("文件读取失败", e);
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

        // 2. 保存文档记录
        Document doc = new Document();
        doc.setTitle(StrUtil.isBlank(title) ? file.getOriginalFilename() : title);
        doc.setCategory(category);
        doc.setDepartment(StrUtil.isBlank(department) ? "" : department);
        doc.setContent(text);
        doc.setFileType(getFileType(file.getOriginalFilename()));
        doc.setStatus("PENDING");
        doc.setChunkCount(0);
        doc.setCreateTime(LocalDateTime.now());
        documentMapper.insert(doc);

        // 3. 发送MQ消息，异步处理
        rabbitTemplate.convertAndSend(DOCUMENT_PROCESS_EXCHANGE, DOCUMENT_PROCESS_ROUTING_KEY, doc.getId());

        log.info("文档上传成功: id={}, title={}, 字符数={}", doc.getId(), doc.getTitle(), text.length());
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
    private String extractText(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename == null) {
            throw new IOException("文件名为空");
        }

        String ext = filename.toLowerCase();
        // 提取扩展名（含点，如 ".pdf"）
        int dotIndex = ext.lastIndexOf('.');
        if (dotIndex < 0) {
            throw new IOException("无法识别文件类型，请上传 .txt / .md / .pdf 格式的文件");
        }
        ext = ext.substring(dotIndex);

        DocumentConverter converter = converterMap.get(ext);
        if (converter == null) {
            throw new IOException("不支持的文件格式: " + ext + "，当前支持: " + converterMap.keySet());
        }

        return converter.convert(file.getBytes(), filename);
    }

    private String getFileType(String filename) {
        if (filename == null) return "UNKNOWN";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf")) return "PDF";
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".txt")) return "TXT";
        return "UNKNOWN";
    }
}
