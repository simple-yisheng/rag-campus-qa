package com.rag.campus.eval;

import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.support.VectorStore;

import java.util.*;

/**
 * 基于关键词匹配的向量存储（仅用于评测）
 * <p>
 * 替代真实向量语义检索，用查询词与 chunk 文本的关键词重叠度模拟相似度打分。
 * 这样评测结果只取决于检索后处理管道（关键词加权、标题匹配、阈值过滤、多样性截断），
 * 不受 Embedding API 不确定性的干扰。
 * <p>
 * 匹配策略：
 * 1. 将查询分词（按中文常见分隔符切分）
 * 2. 每个 chunk 的得分 = 命中词数 / 查询总词数（归一化到 0-1）
 * 3. 完全无匹配返回最低分 0.30（模拟弱相关）
 */
class KeywordVectorStore implements VectorStore {

    private final Map<Long, DocumentChunk> chunks = new LinkedHashMap<>();
    final Map<Long, Document> documents = new LinkedHashMap<>();
    private long chunkIdSeq = 1;
    private long docIdSeq = 1;

    // ==================== 数据加载 API ====================

    long nextChunkId() { return chunkIdSeq++; }
    long nextDocId() { return docIdSeq++; }

    void putChunk(long id, DocumentChunk chunk) { chunks.put(id, chunk); }
    void putDocument(long id, Document doc) { documents.put(id, doc); }

    Document getDocument(long docId) { return documents.get(docId); }
    long totalChunks() { return chunks.size(); }

    // ==================== VectorStore 接口实现 ====================

    @Override
    public void add(Long chunkId, float[] vector, DocumentChunk chunk) {
        chunks.put(chunkId, chunk);
    }

    @Override
    public void addBatch(List<Long> chunkIds, List<float[]> vectors, List<DocumentChunk> chunkList) {
        for (int i = 0; i < chunkIds.size(); i++) {
            chunks.put(chunkIds.get(i), chunkList.get(i));
        }
    }

    /** 最近一次查询文本 —— 由 search() 调用之前通过外部设置 */
    private String lastQuery = "";

    void setQuery(String query) { this.lastQuery = query; }

    @Override
    public List<Hit> search(float[] queryVector, int topK) {
        // 用关键词匹配模拟向量语义检索：查询词与 chunk 文本的重叠度 → 得分
        return keywordSearch(lastQuery, topK);
    }

    /**
     * 基于关键词重叠度的搜索，模拟语义检索的初步召回。
     * 对中文查询使用字符级 n-gram（2-4 字）切分，提升对未分词查询的匹配鲁棒性。
     */
    private List<Hit> keywordSearch(String query, int topK) {
        if (query == null || query.isBlank()) return Collections.emptyList();

        // 1. 提取查询中的关键词（n-gram + 空格分词双路）
        String cleaned = query.replaceAll("[，。？?！!、；;：:\\s\"\"''（）()]+", "").trim();

        List<String> queryWords = new ArrayList<>();
        // 路径1: 空格分词
        for (String w : query.split("\\s+")) {
            if (w.length() >= 2) queryWords.add(w);
        }
        // 路径2: 字符级 n-gram（2/3/4字），弥补中文无分词的缺陷
        if (cleaned.length() >= 2) {
            for (int len = 2; len <= 4; len++) {
                for (int i = 0; i + len <= cleaned.length(); i++) {
                    String gram = cleaned.substring(i, i + len);
                    if (!queryWords.contains(gram)) {
                        queryWords.add(gram);
                    }
                }
            }
        }
        if (queryWords.isEmpty()) queryWords.add(cleaned);

        // 去重
        List<String> uniqueWords = new ArrayList<>(new LinkedHashSet<>(queryWords));

        // 2. 对每个 chunk 计算命中率
        List<Hit> scored = new ArrayList<>();
        for (Map.Entry<Long, DocumentChunk> entry : chunks.entrySet()) {
            Long id = entry.getKey();
            String text = entry.getValue().getChunkText();
            if (text == null) continue;

            int matched = 0;
            for (String word : uniqueWords) {
                if (text.contains(word)) matched++;
            }

            float score;
            if (matched == 0) {
                score = 0.30f;
            } else if (matched >= uniqueWords.size() * 0.7) {
                score = 0.70f + (0.25f * (float) matched / uniqueWords.size());
            } else {
                score = 0.30f + (0.55f * (float) matched / uniqueWords.size());
            }
            score = Math.min(0.95f, score);

            scored.add(new Hit(id, score));
        }

        // 3. 按得分降序排列，返回 topK
        scored.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return scored.subList(0, Math.min(topK, scored.size()));
    }

    @Override
    public DocumentChunk getChunk(Long chunkId) {
        return chunks.get(chunkId);
    }

    @Override
    public int size() {
        return chunks.size();
    }

    @Override
    public void removeByDocumentId(Long documentId) {
        chunks.entrySet().removeIf(e -> e.getValue().getDocumentId().equals(documentId));
    }

    @Override
    public void reload() {
        // no-op for test store
    }
}
