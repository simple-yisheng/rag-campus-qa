package com.rag.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体 — 一轮 Q&A（question + answer）
 */
@Data
@TableName("tb_conversation_message")
public class ConversationMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话的短ID（关联 tb_conversation_session.session_id） */
    private String sessionId;

    /** 用户问题 */
    private String question;

    /** 系统回答 */
    private String answer;

    /** 引用来源（JSON） */
    private String sources;

    private LocalDateTime createTime;
}
