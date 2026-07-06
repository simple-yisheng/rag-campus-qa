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
        /** 文档标题 */
        private String title;
        /** chunk序号 */
        private Integer chunkIndex;
        /** 相似度得分 */
        private Double score;
        /** chunk文本片段（前100字） */
        private String snippet;
    }
}
