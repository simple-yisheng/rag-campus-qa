package com.rag.campus.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块实体 — 每个chunk对应文档的一个语义片段
 */
@Data
@TableName("tb_document_chunk")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属文档ID */
    private Long documentId;

    /** 分块序号，从0开始 */
    private Integer chunkIndex;

    /** 分块文本内容 */
    private String chunkText;

    /**
     * 向量数据，JSON格式的float数组
     * 例如: [0.0123, -0.0456, 0.0789, ...]
     * 维度取决于 Embedding 模型（text-embedding-v3 为 1024 维）
     */
    private String embedding;

    private LocalDateTime createTime;
}
