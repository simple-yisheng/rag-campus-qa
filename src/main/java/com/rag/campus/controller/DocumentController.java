package com.rag.campus.controller;

import com.rag.campus.common.Result;
import com.rag.campus.dto.DocumentUploadResult;
import com.rag.campus.entity.Document;
import com.rag.campus.service.DocumentService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 文档管理接口
 */
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
}
