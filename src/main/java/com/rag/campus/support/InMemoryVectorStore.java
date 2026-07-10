package com.rag.campus.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.DocumentChunkMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存向量存储实现
 * <p>
 * 内存中维护所有 chunk 向量，检索时遍历计算余弦相似度返回 Top-K。
 * <p>
 * 适用场景：
 * - 开发/Demo 阶段（数据量 < 1 万条）
 * - 快速原型验证
 * <p>
 * 性能说明：
 * - 1000 个 1024 维向量，遍历计算余弦相似度约耗时 5-10ms
 * - 万级以内都能接受，超过 10 万建议切到 MilvusVectorStore
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "rag.vector.store", havingValue = "memory", matchIfMissing = true)
public class InMemoryVectorStore implements VectorStore {

    private final DocumentChunkMapper chunkMapper;

    /**
     * 内存向量索引: chunkId → 向量数组
     * ConcurrentHashMap 保证并发安全
     */
    private final Map<Long, float[]> vectorIndex = new ConcurrentHashMap<>();

    /**
     * chunkId → chunk 实体（用于检索后回表取文本）
     */
    private final Map<Long, DocumentChunk> chunkCache = new ConcurrentHashMap<>();

    /**
     * 启动时从 DB 加载所有已完成向量化的 chunk
     */
    @PostConstruct
    public void loadFromDB() {
        log.info("开始从数据库加载向量索引...");
        List<DocumentChunk> chunks = chunkMapper.selectAllWithEmbedding();
        for (DocumentChunk chunk : chunks) {
            try {
                float[] vector = parseEmbedding(chunk.getEmbedding());
                vectorIndex.put(chunk.getId(), vector);
                chunkCache.put(chunk.getId(), chunk);
            } catch (Exception e) {
                log.warn("解析chunk[id={}]的向量失败，跳过", chunk.getId(), e);
            }
        }
        log.info("向量索引加载完成: 共 {} 条", vectorIndex.size());
    }

    @Override
    public void add(Long chunkId, float[] vector, DocumentChunk chunk) {
        vectorIndex.put(chunkId, vector);
        chunkCache.put(chunkId, chunk);
    }

    @Override
    public void addBatch(List<Long> chunkIds, List<float[]> vectors, List<DocumentChunk> chunks) {
        for (int i = 0; i < chunkIds.size(); i++) {
            vectorIndex.put(chunkIds.get(i), vectors.get(i));
            chunkCache.put(chunkIds.get(i), chunks.get(i));
        }
        log.info("向量索引批量添加: {} 条", chunkIds.size());
    }

    @Override
    public List<VectorStore.Hit> search(float[] queryVector, int topK) {
        if (vectorIndex.isEmpty()) {
            log.warn("向量索引为空，请先上传文档！");
            return Collections.emptyList();
        }

        // 计算所有 chunk 与查询向量的余弦相似度
        PriorityQueue<VectorStore.Hit> minHeap = new PriorityQueue<>(
                Comparator.comparingDouble(VectorStore.Hit::getScore)
        );

        for (Map.Entry<Long, float[]> entry : vectorIndex.entrySet()) {
            float[] chunkVector = entry.getValue();
            double similarity = cosineSimilarity(queryVector, chunkVector);

            VectorStore.Hit hit = new VectorStore.Hit(entry.getKey(), (float) similarity);
            if (minHeap.size() < topK) {
                minHeap.offer(hit);
            } else if (similarity > minHeap.peek().getScore()) {
                minHeap.poll();
                minHeap.offer(hit);
            }
        }

        // 转为降序列表
        List<VectorStore.Hit> results = new ArrayList<>(minHeap);
        results.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return results;
    }

    @Override
    public DocumentChunk getChunk(Long chunkId) {
        return chunkCache.get(chunkId);
    }

    @Override
    public int size() {
        return vectorIndex.size();
    }

    @Override
    public void removeByDocumentId(Long documentId) {
        List<Long> toRemove = new ArrayList<>();
        for (Map.Entry<Long, DocumentChunk> entry : chunkCache.entrySet()) {
            if (documentId.equals(entry.getValue().getDocumentId())) {
                toRemove.add(entry.getKey());
            }
        }
        for (Long chunkId : toRemove) {
            vectorIndex.remove(chunkId);
            chunkCache.remove(chunkId);
        }
        if (!toRemove.isEmpty()) {
            log.info("向量索引移除文档[id={}]的 {} 条chunk", documentId, toRemove.size());
        }
    }

    @Override
    public void reload() {
        vectorIndex.clear();
        chunkCache.clear();
        loadFromDB();
    }

    // ==================== 内部方法 ====================

    /**
     * 余弦相似度
     * cos(θ) = (A · B) / (|A| × |B|)
     * 值域 [-1, 1]，越大越相似
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /**
     * 从 JSON 字符串解析向量
     */
    private float[] parseEmbedding(String embeddingJson) {
        JSONArray array = JSONUtil.parseArray(embeddingJson);
        float[] vector = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            vector[i] = array.getFloat(i).floatValue();
        }
        return vector;
    }
}
