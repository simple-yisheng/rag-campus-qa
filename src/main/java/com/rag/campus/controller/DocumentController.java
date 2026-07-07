package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import com.rag.campus.service.DocumentService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
     * 下载/预览文档原始文件
     * <p>
     * GET /api/documents/{id}/file
     * <p>
     * 浏览器直接渲染 PDF/图片，Word/其他格式触发下载。
     * 前端可通过 <iframe src="/api/documents/{id}/file"> 或新窗口打开预览。
     */
    @GetMapping("/{id}/file")
    public void downloadFile(@PathVariable Long id, HttpServletResponse response) {
        Document doc = documentService.getById(id);
        if (doc == null) {
            response.setStatus(404);
            return;
        }

        InputStream stream = documentService.getFileStream(id);
        if (stream == null) {
            response.setStatus(404);
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
            String disposition = isInlinePreview(doc.getFileType())
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
     * 查询所有文档
     */
    @GetMapping
    public Result listAll() {
        List<Document> documents = documentService.listAll();
        return Result.ok(documents);
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
