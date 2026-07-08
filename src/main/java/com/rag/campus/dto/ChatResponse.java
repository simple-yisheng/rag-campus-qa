package com.rag.campus.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 对话响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {

    /** 会话ID */
    private String sessionId;

    /** 回答内容 */
    private String answer;

    /** 引用来源列表 */
    private List<SourceInfo> sources;

    /**
     * 引用来源信息
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SourceInfo {
        /** 文档ID */
        private Long documentId;
        /** 文档标题 */
        private String title;
        /** 源文件类型：PDF / DOCX / TXT 等 */
        private String fileType;
        /** chunk序号 */
        private Integer chunkIndex;
        /** PDF 起始页码（1-based，非 PDF 或无法定位时为空） */
        private Integer pageStart;
        /** PDF 结束页码（1-based，非 PDF 或无法定位时为空） */
        private Integer pageEnd;
        /** 相似度得分 */
        private Double score;
        /** chunk文本片段（前100字） */
        private String snippet;
    }
}
