package com.rag.campus.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rag.campus.entity.DocumentChunk;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 文档分块 Mapper
 */
public interface DocumentChunkMapper extends BaseMapper<DocumentChunk> {

    /** 查询所有已完成向量化的chunk（embedding不为空） */
    @Select("SELECT * FROM tb_document_chunk WHERE embedding IS NOT NULL")
    List<DocumentChunk> selectAllWithEmbedding();

    /** 查询某文档下所有已完成向量化的chunk */
    @Select("SELECT * FROM tb_document_chunk WHERE document_id = #{documentId} AND embedding IS NOT NULL ORDER BY chunk_index")
    List<DocumentChunk> selectByDocumentIdWithEmbedding(Long documentId);
}
