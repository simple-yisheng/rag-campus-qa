package com.rag.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话记录实体
 */
@Data
@TableName("tb_conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话ID（UUID），用于关联多轮对话 */
    private String sessionId;

    /** 用户问题 */
    private String question;

    /** 系统回答 */
    private String answer;

    /**
     * 引用来源，JSON格式
     * 例如: [{"title":"本科生奖学金评定办法","chunkIndex":3,"score":0.95}]
     */
    private String sources;

    private LocalDateTime createTime;
}
