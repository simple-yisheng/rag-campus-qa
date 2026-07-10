package com.rag.campus.support;

import com.rag.campus.entity.DocumentChunk;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 向量存储器接口
 * <p>
 * 策略模式：内存实现（Demo/开发）和 Milvus 实现（生产）可互换。
 * Spring 通过 {@code @ConditionalOnProperty("rag.vector.store")} 自动注入对应实现。
 *
 * <pre>
 *   memory  — InMemoryVectorStore（ConcurrentHashMap + 余弦相似度遍历）
 *   milvus  — MilvusVectorStore（Milvus 向量数据库，COSINE 检索）
 * </pre>
 *
 * <p>
 * 面试要点：
 * 1. 为什么抽象为接口？— 向量存储是可变决策点，接口化后切换成本趋近于零
 * 2. 两种实现各自适合什么场景？— 内存适合 <1 万条快速原型，Milvus 适合百万级生产
 * 3. 换向量数据库对业务代码有影响吗？— 无影响，调用方只依赖接口
 */
public interface VectorStore {

    /**
     * 添加一条向量到索引
     */
    void add(Long chunkId, float[] vector, DocumentChunk chunk);

    /**
     * 批量添加向量
     */
    void addBatch(List<Long> chunkIds, List<float[]> vectors, List<DocumentChunk> chunks);

    /**
     * 语义检索 — Top-K 相似 chunk
     *
     * @param queryVector 查询向量
     * @param topK        返回最相似的 K 个结果
     * @return 相似 chunk 列表 + 得分，按相似度降序排列
     */
    List<VectorStore.Hit> search(float[] queryVector, int topK);

    /**
     * 根据 chunkId 获取 chunk 实体（用于检索后回表取文本）
     */
    DocumentChunk getChunk(Long chunkId);

    /**
     * 向量索引大小
     */
    int size();

    /**
     * 按文档 ID 移除所有关联的向量（删除文档时调用）
     */
    void removeByDocumentId(Long documentId);

    /**
     * 清空并重载（用于调试或迁移后刷新）
     */
    void reload();

    // ==================== 内部类 ====================

    /**
     * 检索命中结果
     */
    @Data
    @AllArgsConstructor
    class Hit {
        private Long chunkId;
        private float score;
    }
}
