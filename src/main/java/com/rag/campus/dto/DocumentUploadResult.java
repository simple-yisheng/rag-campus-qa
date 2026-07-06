package com.rag.campus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文档上传结果 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentUploadResult {

    /** 文档ID */
    private Long documentId;

    /** 文档标题 */
    private String title;

    /** 处理状态 */
    private String status;

    /** 提示信息 */
    private String message;
}
