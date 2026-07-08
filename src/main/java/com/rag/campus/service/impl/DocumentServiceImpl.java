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
import com.rag.campus.support.OfficePreviewService;
import com.rag.campus.support.VectorStore;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
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
import java.util.concurrent.ConcurrentHashMap;

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
    private final OfficePreviewService officePreviewService;

    /** 扩展名 → 转换器映射（启动时构建） */
    private final Map<String, DocumentConverter> converterMap = new HashMap<>();

    /** 原始文件提取后的完整文本缓存，用于参考资料抽屉快速展示和定位。 */
    private final Map<Long, String> displayContentCache = new ConcurrentHashMap<>();

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
                               MinioStorageService minioStorage,
                               OfficePreviewService officePreviewService) {
        this.documentMapper = documentMapper;
        this.chunkMapper = chunkMapper;
        this.embeddingClient = embeddingClient;
        this.vectorStore = vectorStore;
        this.rabbitTemplate = rabbitTemplate;
        this.converters = converters;
        this.minioStorage = minioStorage;
        this.officePreviewService = officePreviewService;
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

        // 6. Word 文档生成 PDF 预览，供前端复用 PDF.js 原格式展示。
        if (isWordDocument(doc.getFileType()) && StrUtil.isNotBlank(doc.getFileKey())) {
            try {
                byte[] previewPdf = officePreviewService.convertWordToPdf(fileBytes, originalName);
                String previewName = buildPreviewFilename(originalName);
                String previewKey = minioStorage.upload(doc.getId(), previewName, previewPdf, "application/pdf");
                doc.setPreviewFileKey(previewKey);
                documentMapper.updateById(doc);
            } catch (Exception e) {
                log.warn("Word预览PDF生成失败，将使用文本兜底展示: documentId={}, filename={}", doc.getId(), originalName, e);
            }
        }

        // 7. 发送MQ消息，异步处理
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
    public InputStream getPreviewFileStream(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) {
            return null;
        }
        if ("PDF".equals(doc.getFileType()) && StrUtil.isNotBlank(doc.getFileKey())) {
            return minioStorage.download(doc.getFileKey());
        }
        if (isWordDocument(doc.getFileType()) && StrUtil.isNotBlank(doc.getPreviewFileKey())) {
            return minioStorage.download(doc.getPreviewFileKey());
        }
        return null;
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
        if (StrUtil.isNotBlank(doc.getPreviewFileKey())) {
            minioStorage.delete(doc.getPreviewFileKey());
        }

        // 删除向量索引
        vectorStore.removeByDocumentId(documentId);
        displayContentCache.remove(documentId);

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

            List<PageRange> pageRanges = inferPdfPageRanges(doc, chunkTexts);

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
                if (i < pageRanges.size()) {
                    PageRange pageRange = pageRanges.get(i);
                    chunk.setPageStart(pageRange.start());
                    chunk.setPageEnd(pageRange.end());
                }
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

    @Override
    public String getDocumentContent(Long documentId) {
        Document doc = documentMapper.selectById(documentId);
        if (doc == null) return null;

        String cached = displayContentCache.get(documentId);
        if (StrUtil.isNotBlank(cached)) {
            return cached;
        }

        if (StrUtil.isNotBlank(doc.getFileKey())) {
            try (InputStream stream = getFileStream(documentId)) {
                if (stream != null) {
                    String text = extractText(stream.readAllBytes(), getOriginalFilename(doc));
                    displayContentCache.put(documentId, text);
                    return text;
                }
            } catch (Exception e) {
                log.warn("从原始文件提取展示全文失败，将回退到 chunks 拼接: documentId={}", documentId, e);
            }
        }

        if (StrUtil.isNotBlank(doc.getContent())) {
            return doc.getContent();
        }

        List<DocumentChunk> chunks = getDocumentChunks(documentId);

        if (chunks.isEmpty()) return null;

        return chunks.stream()
                .map(DocumentChunk::getChunkText)
                .filter(StrUtil::isNotBlank)
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    @Override
    public List<DocumentChunk> getDocumentChunks(Long documentId) {
        return chunkMapper.selectList(
                new LambdaQueryWrapper<DocumentChunk>()
                        .eq(DocumentChunk::getDocumentId, documentId)
                        .orderByAsc(DocumentChunk::getChunkIndex)
        );
    }

    private List<PageRange> inferPdfPageRanges(Document doc, List<String> chunkTexts) {
        List<PageRange> ranges = new ArrayList<>();
        if (!hasPdfPreview(doc)) {
            return ranges;
        }

        try {
            PdfPageIndex pageIndex = buildPdfPageIndex(doc);
            if (pageIndex.normalizedText().isEmpty()) {
                return ranges;
            }

            int searchFrom = 0;
            for (String chunkText : chunkTexts) {
                LocatedPageRange located = locateChunkInPdf(chunkText, pageIndex, searchFrom);
                if (located == null) {
                    ranges.add(new PageRange(null, null));
                    continue;
                }
                ranges.add(new PageRange(located.pageStart(), located.pageEnd()));
                searchFrom = Math.max(0, located.normalizedStart() - Math.max(20, chunkOverlap));
            }
            log.info("PDF页码定位完成: documentId={}, located={}/{}",
                    doc.getId(), ranges.stream().filter(PageRange::located).count(), chunkTexts.size());
        } catch (Exception e) {
            log.warn("PDF页码定位失败，将不写入chunk页码: documentId={}", doc.getId(), e);
            return new ArrayList<>();
        }
        return ranges;
    }

    private PdfPageIndex buildPdfPageIndex(Document doc) throws IOException {
        StringBuilder normalizedText = new StringBuilder();
        List<Integer> pageByChar = new ArrayList<>();

        try (InputStream stream = getPreviewFileStream(doc.getId());
             PDDocument pdf = PDDocument.load(stream)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);

            for (int page = 1; page <= pdf.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String pageText = stripper.getText(pdf);
                appendNormalizedPageText(pageText, page, normalizedText, pageByChar);
            }
        }

        return new PdfPageIndex(normalizedText.toString(), pageByChar);
    }

    private void appendNormalizedPageText(String text,
                                          int page,
                                          StringBuilder normalizedText,
                                          List<Integer> pageByChar) {
        if (text == null) return;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                normalizedText.append(ch);
                pageByChar.add(page);
            }
        }
    }

    private LocatedPageRange locateChunkInPdf(String chunkText, PdfPageIndex pageIndex, int searchFrom) {
        String normalizedChunk = normalizeForPageMatch(chunkText);
        if (normalizedChunk.length() < 8) return null;

        List<String> candidates = buildPageMatchCandidates(normalizedChunk);
        for (String candidate : candidates) {
            int start = pageIndex.normalizedText().indexOf(candidate, Math.min(searchFrom, pageIndex.normalizedText().length()));
            if (start < 0 && searchFrom > 0) {
                start = pageIndex.normalizedText().indexOf(candidate);
            }
            if (start >= 0) {
                int end = Math.min(start + candidate.length() - 1, pageIndex.pageByChar().size() - 1);
                return new LocatedPageRange(
                        pageIndex.pageByChar().get(start),
                        pageIndex.pageByChar().get(end),
                        start
                );
            }
        }
        return null;
    }

    private List<String> buildPageMatchCandidates(String normalizedChunk) {
        int[] lengths = { normalizedChunk.length(), 300, 200, 120, 80, 40, 24 };
        List<String> candidates = new ArrayList<>();
        for (int length : lengths) {
            int actualLength = Math.min(length, normalizedChunk.length());
            if (actualLength >= 8) {
                String candidate = normalizedChunk.substring(0, actualLength);
                if (!candidates.contains(candidate)) {
                    candidates.add(candidate);
                }
            }
        }
        return candidates;
    }

    private String normalizeForPageMatch(String text) {
        if (text == null) return "";
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (!Character.isWhitespace(ch)) {
                normalized.append(ch);
            }
        }
        return normalized.toString();
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
        if (lower.endsWith(".docx")) return "DOCX";
        if (lower.endsWith(".doc")) return "DOC";
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) return "MD";
        if (lower.endsWith(".txt")) return "TXT";
        return "UNKNOWN";
    }

    private boolean isWordDocument(String fileType) {
        return "DOCX".equals(fileType) || "DOC".equals(fileType);
    }

    private boolean hasPdfPreview(Document doc) {
        return ("PDF".equals(doc.getFileType()) && StrUtil.isNotBlank(doc.getFileKey()))
                || (isWordDocument(doc.getFileType()) && StrUtil.isNotBlank(doc.getPreviewFileKey()));
    }

    private String buildPreviewFilename(String originalName) {
        String filename = StrUtil.blankToDefault(originalName, "document");
        int dotIndex = filename.lastIndexOf('.');
        String baseName = dotIndex >= 0 ? filename.substring(0, dotIndex) : filename;
        return baseName + ".preview.pdf";
    }

    private String getOriginalFilename(Document doc) {
        String fileKey = doc.getFileKey();
        if (StrUtil.isNotBlank(fileKey)) {
            int slashIndex = Math.max(fileKey.lastIndexOf('/'), fileKey.lastIndexOf('\\'));
            return slashIndex >= 0 ? fileKey.substring(slashIndex + 1) : fileKey;
        }

        String title = StrUtil.blankToDefault(doc.getTitle(), "document");
        String lower = title.toLowerCase();
        if (lower.endsWith(".pdf") || lower.endsWith(".docx") || lower.endsWith(".doc")
                || lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            return title;
        }

        return title + switch (doc.getFileType()) {
            case "PDF" -> ".pdf";
            case "DOCX" -> ".docx";
            case "DOC" -> ".doc";
            case "MD" -> ".md";
            case "TXT" -> ".txt";
            default -> ".txt";
        };
    }

    private record PdfPageIndex(String normalizedText, List<Integer> pageByChar) {}

    private record PageRange(Integer start, Integer end) {
        boolean located() {
            return start != null && end != null;
        }
    }

    private record LocatedPageRange(Integer pageStart, Integer pageEnd, int normalizedStart) {}
}
