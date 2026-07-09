package com.rag.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体 — 一个 session 包含多轮对话
 */
@Data
@TableName("tb_conversation_session")
public class ConversationSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话短ID（如 a3b2k9m1），前端用于标识对话窗口 */
    private String sessionId;

    /** 所属用户ID */
    private Long userId;

    /** 会话标题（取自首条问题，或用户自定义） */
    private String title;

    private LocalDateTime createTime;

    /** 最后活跃时间 */
    private LocalDateTime lastActiveTime;
}
