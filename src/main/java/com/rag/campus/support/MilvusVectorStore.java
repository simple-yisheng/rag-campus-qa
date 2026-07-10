package com.rag.campus.support;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.DocumentChunkMapper;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.RpcStatus;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Milvus 向量存储实现
 * <p>
 * 使用 Milvus 向量数据库进行向量存储和语义检索，支持百万级向量规模。
 * 本地保留 ConcurrentHashMap 作为 chunk 文本缓存，避免每次检索都查 MySQL。
 * <p>
 * 架构说明：
 * <pre>
 *   Milvus（向量检索） + MySQL（文本/元数据）
 *   ┌──────────────┐  search top-K   ┌──────────────────┐
 *   │  Milvus      │ ───────────────→│  chunk_id 列表    │
 *   │  Collection  │                 │  + score          │
 *   └──────────────┘                 └──────┬───────────┘
 *                                          │ getChunk(chunkId)
 *                                          ▼
 *                                   ┌──────────────────┐
 *                                   │ Local ChunkCache │ (ConcurrentHashMap)
 *                                   │ chunk_id → text  │
 *                                   └──────────────────┘
 * </pre>
 * <p>
 * 面试要点：
 * 1. 为什么 Milvus 比内存快？— IVF_FLAT 索引 + 聚类剪枝，不必遍历全部向量
 * 2. 为什么仍然保留本地缓存？— 检索后需要取文本组装 Prompt，避免每次查 MySQL
 * 3. 向量数据库的选择：Milvus（开源/高性能）vs pgvector（简单/与业务DB统一）vs Elasticsearch（全文+向量混合）
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.vector.store", havingValue = "milvus")
public class MilvusVectorStore implements VectorStore {

    private final MilvusServiceClient milvusClient;
    private final DocumentChunkMapper chunkMapper;

    @Value("${milvus.collection:rag_campus_chunks}")
    private String collectionName;

    @Value("${milvus.metric-type:COSINE}")
    private String metricType;

    @Value("${milvus.dimension:1024}")
    private int dimension;

    @Value("${milvus.index-type:IVF_FLAT}")
    private String indexType;

    @Value("${milvus.nlist:128}")
    private int nlist;

    @Value("${milvus.nprobe:16}")
    private int nprobe;

    /**
     * chunkId → chunk 实体（本地文本缓存，检索后快速取文本）
     */
    private final Map<Long, DocumentChunk> chunkCache = new ConcurrentHashMap<>();

    /** 标记是否已成功初始化 */
    private volatile boolean initialized = false;

    public MilvusVectorStore(MilvusServiceClient milvusClient,
                            DocumentChunkMapper chunkMapper) {
        this.milvusClient = milvusClient;
        this.chunkMapper = chunkMapper;
    }

    // ==================== 生命周期 ====================

    /**
     * 启动时初始化 Milvus Collection 和索引。
     * <p>
     * 如果 Collection 为空且 MySQL 中存在已向量化的 chunk（例如刚从内存模式切换过来），
     * 自动从 MySQL 迁移向量数据到 Milvus。
     */
    @PostConstruct
    public void init() {
        try {
            ensureCollection();
            ensureIndex();
            loadCollection();

            // 检查 Milvus 是否为空，若为空则从 MySQL 迁移
            long milvusCount = getMilvusRowCount();
            List<DocumentChunk> allChunks = chunkMapper.selectAllWithEmbedding();

            if (milvusCount == 0 && !allChunks.isEmpty()) {
                log.info("Milvus 为空，开始从 MySQL 迁移 {} 条向量...", allChunks.size());
                migrateFromMySQL(allChunks);
            } else {
                // Milvus 已有数据，只加载本地文本缓存
                for (DocumentChunk chunk : allChunks) {
                    chunkCache.put(chunk.getId(), chunk);
                }
                log.info("Milvus 已有 {} 条向量，本地缓存加载 {} 条", milvusCount, chunkCache.size());
            }

            initialized = true;
            log.info("MilvusVectorStore 初始化完成: collection={}, milvus={}, cache={}",
                    collectionName, milvusCount > 0 ? milvusCount : allChunks.size(), chunkCache.size());
        } catch (Exception e) {
            log.error("MilvusVectorStore 初始化失败，检索功能将不可用", e);
            initialized = false;
        }
    }

    @PreDestroy
    public void destroy() {
        if (milvusClient != null) {
            milvusClient.close();
            log.info("Milvus 客户端已关闭");
        }
    }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void add(Long chunkId, float[] vector, DocumentChunk chunk) {
        if (!initialized) {
            log.warn("MilvusVectorStore 未初始化，跳过 add: chunkId={}", chunkId);
            return;
        }

        addBatch(Collections.singletonList(chunkId),
                Collections.singletonList(vector),
                Collections.singletonList(chunk));
    }

    @Override
    public void addBatch(List<Long> chunkIds, List<float[]> vectors, List<DocumentChunk> chunks) {
        if (!initialized) {
            log.warn("MilvusVectorStore 未初始化，跳过 addBatch: count={}", chunkIds.size());
            return;
        }

        if (chunkIds.isEmpty()) return;

        try {
            // 构建列数据
            List<Long> docIds = new ArrayList<>();
            List<List<Float>> vectorList = new ArrayList<>();

            for (int i = 0; i < chunkIds.size(); i++) {
                DocumentChunk chunk = chunks.get(i);
                docIds.add(chunk.getDocumentId());

                float[] vec = vectors.get(i);
                List<Float> floats = new ArrayList<>(vec.length);
                for (float v : vec) {
                    floats.add(v);
                }
                vectorList.add(floats);
            }

            List<InsertParam.Field> fields = new ArrayList<>();
            fields.add(new InsertParam.Field("chunk_id", chunkIds));
            fields.add(new InsertParam.Field("document_id", docIds));
            fields.add(new InsertParam.Field("embedding", vectorList));

            InsertParam param = InsertParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withFields(fields)
                    .build();

            R<MutationResult> response = milvusClient.insert(param);
            if (response.getStatus() == R.Status.Success.getCode()) {
                log.info("Milvus 插入成功: {} 条向量", chunkIds.size());
            } else {
                log.error("Milvus 插入失败: {}", response.getMessage());
            }

            // 更新本地文本缓存
            for (int i = 0; i < chunkIds.size(); i++) {
                chunkCache.put(chunkIds.get(i), chunks.get(i));
            }

        } catch (Exception e) {
            log.error("Milvus addBatch 异常: count={}", chunkIds.size(), e);
        }
    }

    @Override
    public List<VectorStore.Hit> search(float[] queryVector, int topK) {
        if (!initialized) {
            log.warn("MilvusVectorStore 未初始化，返回空结果");
            return Collections.emptyList();
        }

        if (chunkCache.isEmpty()) {
            log.warn("Milvus 向量索引为空，请先上传文档！");
            return Collections.emptyList();
        }

        try {
            // 转换查询向量
            List<Float> queryFloatList = new ArrayList<>(queryVector.length);
            for (float v : queryVector) {
                queryFloatList.add(v);
            }
            List<List<Float>> queryVectors = Collections.singletonList(queryFloatList);

            SearchParam param = SearchParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withMetricType(MetricType.valueOf(metricType))
                    .withTopK(topK)
                    .withFloatVectors(queryVectors)
                    .withVectorFieldName("embedding")
                    .withParams("{\"nprobe\":" + nprobe + "}")
                    .build();

            R<SearchResults> response = milvusClient.search(param);

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("Milvus 检索失败: {}", response.getMessage());
                return Collections.emptyList();
            }

            SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
            List<SearchResultsWrapper.IDScore> idScores = wrapper.getIDScore(0);

            if (idScores == null || idScores.isEmpty()) {
                return Collections.emptyList();
            }

            // 转换为 Hit 列表（chunk_id 即主键，通过 getLongID() 获取）
            List<VectorStore.Hit> hits = new ArrayList<>();
            for (SearchResultsWrapper.IDScore idScore : idScores) {
                Long chunkId = idScore.getLongID();
                // 只保留 chunk 缓存中存在的（确保文档未被删除）
                if (chunkCache.containsKey(chunkId)) {
                    hits.add(new VectorStore.Hit(chunkId, idScore.getScore()));
                }
            }

            log.debug("Milvus 检索完成: topK={}, 命中={}", topK, hits.size());
            return hits;

        } catch (Exception e) {
            log.error("Milvus 检索异常", e);
            return Collections.emptyList();
        }
    }

    @Override
    public DocumentChunk getChunk(Long chunkId) {
        return chunkCache.get(chunkId);
    }

    @Override
    public int size() {
        return chunkCache.size();
    }

    @Override
    public void removeByDocumentId(Long documentId) {
        if (!initialized) {
            log.warn("MilvusVectorStore 未初始化，跳过 removeByDocumentId: {}", documentId);
            return;
        }

        try {
            // 从 Milvus 删除
            DeleteParam param = DeleteParam.newBuilder()
                    .withCollectionName(collectionName)
                    .withExpr("document_id == " + documentId)
                    .build();

            R<MutationResult> response = milvusClient.delete(param);
            if (response.getStatus() == R.Status.Success.getCode()) {
                log.info("Milvus 删除文档向量成功: documentId={}", documentId);
            } else {
                log.error("Milvus 删除文档向量失败: {}", response.getMessage());
            }

            // 从本地缓存删除
            List<Long> toRemove = chunkCache.entrySet().stream()
                    .filter(e -> documentId.equals(e.getValue().getDocumentId()))
                    .map(Map.Entry::getKey)
                    .toList();
            toRemove.forEach(chunkCache::remove);
            log.info("本地缓存移除文档[id={}]的 {} 条chunk", documentId, toRemove.size());

        } catch (Exception e) {
            log.error("Milvus removeByDocumentId 异常: documentId={}", documentId, e);
        }
    }

    @Override
    public void reload() {
        try {
            milvusClient.releaseCollection(
                    io.milvus.param.collection.ReleaseCollectionParam.newBuilder()
                            .withCollectionName(collectionName)
                            .build());
            loadCollection();

            chunkCache.clear();
            List<DocumentChunk> chunks = chunkMapper.selectAllWithEmbedding();
            for (DocumentChunk chunk : chunks) {
                chunkCache.put(chunk.getId(), chunk);
            }
            log.info("MilvusVectorStore 重载完成: {} 条", chunkCache.size());
        } catch (Exception e) {
            log.error("MilvusVectorStore 重载失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 获取 Milvus Collection 中的行数
     */
    private long getMilvusRowCount() {
        try {
            R<io.milvus.grpc.GetCollectionStatisticsResponse> stats =
                    milvusClient.getCollectionStatistics(
                            io.milvus.param.collection.GetCollectionStatisticsParam.newBuilder()
                                    .withCollectionName(collectionName)
                                    .build());
            if (stats.getStatus() == R.Status.Success.getCode()) {
                for (io.milvus.grpc.KeyValuePair kv : stats.getData().getStatsList()) {
                    if ("row_count".equals(kv.getKey())) {
                        return Long.parseLong(kv.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取 Milvus 行数失败", e);
        }
        return 0;
    }

    /**
     * 从 MySQL 迁移向量到 Milvus（首次切换或数据恢复时调用）
     * <p>
     * 读取 tb_document_chunk 中所有已向量化的记录，解析 JSON embedding，
     * 分批插入 Milvus，同时填充本地文本缓存。
     * <p>
     * 批次大小 100 条，避免单次插入过大。
     */
    private void migrateFromMySQL(List<DocumentChunk> allChunks) {
        int batchSize = 100;
        int migrated = 0;

        for (int i = 0; i < allChunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, allChunks.size());
            List<DocumentChunk> batch = allChunks.subList(i, end);

            List<Long> chunkIds = new ArrayList<>();
            List<Long> docIds = new ArrayList<>();
            List<List<Float>> vectorList = new ArrayList<>();

            for (DocumentChunk chunk : batch) {
                try {
                    float[] vector = parseEmbedding(chunk.getEmbedding());
                    if (vector.length != dimension) {
                        log.warn("chunk[id={}] 向量维度不匹配: expected={}, actual={}, 跳过",
                                chunk.getId(), dimension, vector.length);
                        continue;
                    }

                    chunkIds.add(chunk.getId());
                    docIds.add(chunk.getDocumentId());

                    List<Float> floats = new ArrayList<>(vector.length);
                    for (float v : vector) {
                        floats.add(v);
                    }
                    vectorList.add(floats);

                    // 同时填充本地文本缓存
                    chunkCache.put(chunk.getId(), chunk);
                } catch (Exception e) {
                    log.warn("解析chunk[id={}]的向量失败，跳过", chunk.getId(), e);
                }
            }

            if (chunkIds.isEmpty()) continue;

            try {
                List<InsertParam.Field> fields = new ArrayList<>();
                fields.add(new InsertParam.Field("chunk_id", chunkIds));
                fields.add(new InsertParam.Field("document_id", docIds));
                fields.add(new InsertParam.Field("embedding", vectorList));

                InsertParam param = InsertParam.newBuilder()
                        .withCollectionName(collectionName)
                        .withFields(fields)
                        .build();

                R<MutationResult> response = milvusClient.insert(param);
                if (response.getStatus() == R.Status.Success.getCode()) {
                    migrated += chunkIds.size();
                    log.info("MySQL→Milvus 迁移进度: {}/{}", migrated, allChunks.size());
                } else {
                    log.error("MySQL→Milvus 迁移失败(batch): {}", response.getMessage());
                }
            } catch (Exception e) {
                log.error("MySQL→Milvus 迁移异常(batch)", e);
            }
        }

        log.info("MySQL→Milvus 迁移完成: 共迁移 {} 条向量", migrated);
    }

    /**
     * 从 JSON 字符串解析向量
     */
    private float[] parseEmbedding(String embeddingJson) {
        if (embeddingJson == null || embeddingJson.isBlank()) {
            return new float[0];
        }
        JSONArray array = JSONUtil.parseArray(embeddingJson);
        float[] vector = new float[array.size()];
        for (int i = 0; i < array.size(); i++) {
            vector[i] = array.getFloat(i).floatValue();
        }
        return vector;
    }

    /**
     * 确保 Collection 存在，不存在则创建
     */
    private void ensureCollection() {
        HasCollectionParam hasParam = HasCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();

        R<Boolean> hasCollection = milvusClient.hasCollection(hasParam);
        if (hasCollection.getStatus() == R.Status.Success.getCode() && hasCollection.getData()) {
            log.info("Milvus Collection 已存在: {}", collectionName);
            return;
        }

        log.info("创建 Milvus Collection: {}", collectionName);

        // 定义字段 Schema
        FieldType chunkIdField = FieldType.newBuilder()
                .withName("chunk_id")
                .withDataType(DataType.Int64)
                .withPrimaryKey(true)
                .withAutoID(false)
                .build();

        FieldType documentIdField = FieldType.newBuilder()
                .withName("document_id")
                .withDataType(DataType.Int64)
                .build();

        FieldType embeddingField = FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(dimension)
                .build();

        CreateCollectionParam createParam = CreateCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .withDescription("校园智答 — 文档分块向量库")
                .addFieldType(chunkIdField)
                .addFieldType(documentIdField)
                .addFieldType(embeddingField)
                .build();

        R<RpcStatus> response = milvusClient.createCollection(createParam);
        if (response.getStatus() == R.Status.Success.getCode()) {
            log.info("Milvus Collection 创建成功: {}", collectionName);
        } else {
            log.error("Milvus Collection 创建失败: {}", response.getMessage());
            throw new RuntimeException("Milvus Collection 创建失败: " + response.getMessage());
        }
    }

    /**
     * 确保向量索引存在
     */
    private void ensureIndex() {
        CreateIndexParam param = CreateIndexParam.newBuilder()
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(IndexType.valueOf(indexType))
                .withMetricType(MetricType.valueOf(metricType))
                .withExtraParam("{\"nlist\":" + nlist + "}")
                .build();

        R<RpcStatus> response = milvusClient.createIndex(param);
        if (response.getStatus() == R.Status.Success.getCode()) {
            log.info("Milvus 索引创建/确认成功: collection={}, indexType={}", collectionName, indexType);
        } else {
            // 索引可能已存在，仅 warn
            log.warn("Milvus 索引创建返回: {}", response.getMessage());
        }
    }

    /**
     * 加载 Collection 到内存
     */
    private void loadCollection() {
        LoadCollectionParam param = LoadCollectionParam.newBuilder()
                .withCollectionName(collectionName)
                .build();

        R<RpcStatus> response = milvusClient.loadCollection(param);
        if (response.getStatus() == R.Status.Success.getCode()) {
            log.info("Milvus Collection 加载成功: {}", collectionName);
        } else {
            log.warn("Milvus Collection 加载返回: {}", response.getMessage());
        }
    }
}
