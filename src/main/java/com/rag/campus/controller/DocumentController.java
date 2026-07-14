package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.service.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档管理接口
 */
@Slf4j
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
public class DocumentController {

    private final DocumentService documentService;

    /**
     * 上传文档
     * <p>
     * POST /api/documents/upload
     * form-data:
     *   file:      (必填) 文档文件
     *   title:     (选填) 文档标题，不填则使用文件名
     *   category:  (必填) 分类: POLICY / SCHOLARSHIP / ACADEMIC / GUIDE / OTHER
     *   department:(选填) 发布单位
     */
    @PostMapping("/upload")
    public Result upload(@RequestParam("file") MultipartFile file,
                         @RequestParam(value = "title", required = false) String title,
                         @RequestParam("category") String category,
                         @RequestParam(value = "department", required = false) String department) {
        if (file.isEmpty()) {
            return Result.fail("文件不能为空");
        }
        DocumentUploadResult result = documentService.upload(file, title, category, department);
        return Result.ok(result);
    }

    /**
     * 删除文档
     */
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable Long id) {
        documentService.delete(id);
        return Result.ok(null);
    }

    /**
     * 审核文档（仅管理员）
     * <p>
     * PUT /api/documents/{id}/review
     * Body: { "approved": true }  通过审核，自动进入分块+向量化处理
     * Body: { "approved": false } 驳回，文档不可检索
     */
    @PutMapping("/{id}/review")
    public Result review(@PathVariable Long id, @RequestBody Map<String, Boolean> body) {
        Boolean approved = body.get("approved");
        if (approved == null) {
            return Result.fail("缺少 approved 参数");
        }
        try {
            documentService.reviewDocument(id, approved);
            return Result.ok(approved ? "审核通过，文档进入后台处理" : "已驳回");
        } catch (IllegalArgumentException e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 下载/预览文档原始文件
     * <p>
     * GET /api/documents/{id}/file
     * <p>
     * 浏览器直接渲染 PDF/图片，Word/其他格式触发下载。
     * 前端可通过 <iframe src="/api/documents/{id}/file"> 或新窗口打开预览。
     */
    @GetMapping("/{id}/file")
    public void downloadFile(@PathVariable Long id,
                             @RequestParam(value = "download", defaultValue = "false") boolean download,
                             HttpServletResponse response) {
        Document doc = documentService.getById(id);
        if (doc == null) {
            response.setStatus(404);
            return;
        }

        InputStream stream = documentService.getFileStream(id);
        if (stream == null) {
            // 老文档未存储原始文件，返回提示页面
            try {
                response.setContentType("text/html; charset=UTF-8");
                response.getWriter().write("""
                    <html><head><meta charset="UTF-8"></head><body style="padding:40px;font-family:sans-serif">
                    <h3>该文档暂无原始文件</h3>
                    <p>原因：此文档上传时尚未启用 MinIO 对象存储，原始文件未保留。</p>
                    <p>建议：重新上传该文件即可保存原始版本。</p>
                    </body></html>""");
            } catch (IOException ignored) {}
            return;
        }

        try (stream; OutputStream out = response.getOutputStream()) {
            // 根据文件类型设置 Content-Type，PDF/图片可浏览器内预览
            String contentType = getContentType(doc.getFileType());
            response.setContentType(contentType);

            // 文件名处理（支持中文）
            String encodedName = URLEncoder.encode(doc.getTitle(), StandardCharsets.UTF_8)
                    .replace("+", "%20");
            // inline: PDF/图片在浏览器内预览；其他类型浏览器自行决定
            String disposition = !download && isInlinePreview(doc.getFileType())
                    ? "inline"
                    : "attachment";
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    disposition + "; filename*=UTF-8''" + encodedName);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (Exception e) {
            log.error("文件下载失败: documentId={}", id, e);
            response.setStatus(500);
        }
    }

    /**
     * 获取 PDF 预览文件。
     * <p>
     * PDF 原文档直接返回原始 PDF；Word 文档返回 LibreOffice 生成的 PDF 预览。
     */
    @GetMapping("/{id}/preview")
    public void previewFile(@PathVariable Long id, HttpServletResponse response) {
        Document doc = documentService.getById(id);
        if (doc == null) {
            response.setStatus(404);
            return;
        }

        InputStream stream = documentService.getPreviewFileStream(id);
        if (stream == null) {
            response.setStatus(404);
            try {
                response.setContentType("text/plain; charset=UTF-8");
                response.getWriter().write("该文档暂无PDF预览文件");
            } catch (IOException ignored) {}
            return;
        }

        try (stream; OutputStream out = response.getOutputStream()) {
            response.setContentType("application/pdf");
            String encodedName = URLEncoder.encode(doc.getTitle() + ".pdf", StandardCharsets.UTF_8)
                    .replace("+", "%20");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "inline; filename*=UTF-8''" + encodedName);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
        } catch (Exception e) {
            log.error("预览文件读取失败: documentId={}", id, e);
            response.setStatus(500);
        }
    }

    /**
     * 分页查询所有文档
     */
    @GetMapping
    public Result listAll(@RequestParam(defaultValue = "1") int page,
                          @RequestParam(defaultValue = "10") int size) {
        var pageResult = documentService.listAll(page, size);
        Map<String, Object> data = new HashMap<>();
        data.put("records", pageResult.getRecords());
        data.put("total", pageResult.getTotal());
        data.put("current", pageResult.getCurrent());
        data.put("size", pageResult.getSize());
        return Result.ok(data);
    }

    /**
     * 查询单个文档详情
     */
    @GetMapping("/{id}")
    public Result getById(@PathVariable Long id) {
        Document doc = documentService.getById(id);
        if (doc == null) {
            return Result.fail("文档不存在");
        }
        return Result.ok(doc);
    }

    /**
     * 获取文档完整文本内容。
     * <p>
     * GET /api/documents/{id}/content
     * <p>
     * 用于前端参考资料定位：点击参考资料时侧边抽屉展示从原始文件提取的全文，
     * 并使用 chunkIndex 对应的 chunk 文本在全文中定位高亮。
     */
    @GetMapping("/{id}/content")
    public Result getContent(@PathVariable Long id) {
        Document doc = documentService.getById(id);
        if (doc == null) {
            return Result.fail("文档不存在");
        }
        String content = documentService.getDocumentContent(id);
        if (content == null) {
            return Result.fail("文档内容为空，可能尚未处理完成");
        }
        List<DocumentChunk> chunks = documentService.getDocumentChunks(id);
        Map<String, Object> data = new HashMap<>();
        data.put("documentId", doc.getId());
        data.put("title", doc.getTitle());
        data.put("fileType", doc.getFileType());
        data.put("content", content);
        data.put("chunks", chunks.stream()
                .map(chunk -> {
                    Map<String, Object> item = new HashMap<>();
                    item.put("chunkIndex", chunk.getChunkIndex());
                    item.put("text", chunk.getChunkText());
                    return item;
                })
                .toList());
        return Result.ok(data);
    }

    // ==================== 私有方法 ====================

    private String getContentType(String fileType) {
        return switch (fileType) {
            case "PDF" -> "application/pdf";
            case "DOCX", "DOC" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "MD", "TXT" -> "text/plain; charset=UTF-8";
            default -> MediaType.APPLICATION_OCTET_STREAM_VALUE;
        };
    }

    private boolean isInlinePreview(String fileType) {
        return "PDF".equals(fileType);
    }
}
