package com.rag.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档实体 — 校园规章制度、评奖评优政策、生活指南等
 */
@Data
@TableName("tb_document")
public class Document {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档标题（如"XX大学本科生奖学金评定办法"） */
    private String title;

    /**
     * 文档分类：
     * POLICY      — 规章制度（学生手册、宿舍管理等）
     * SCHOLARSHIP — 评奖评优（奖学金、三好学生、优秀毕业生）
     * ACADEMIC    — 学业政策（保研、转专业、辅修）
     * GUIDE       — 生活指南（校园卡、食堂、就医、选课）
     * OTHER       — 其他
     */
    private String category;

    /** 发布单位/学院 */
    private String department;

    /** 文档原始文本内容 */
    private String content;

    /** 源文件类型：TXT / PDF / DOCX */
    private String fileType;

    /**
     * 处理状态：
     * PENDING    — 刚上传，等待处理
     * PROCESSING — 正在分块+向量化
     * DONE       — 处理完成，可检索
     * FAILED     — 处理失败
     */
    private String status;

    /** 分块数量 */
    private Integer chunkCount;

    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
