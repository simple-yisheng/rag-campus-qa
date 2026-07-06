package com.rag.campus.dto;

import lombok.Data;

/**
 * 对话请求 DTO
 */
@Data
public class ChatRequest {

    /** 会话ID，首次提问不传，系统自动生成 */
    private String sessionId;

    /** 用户问题 */
    private String question;
}
