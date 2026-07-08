package com.rag.campus.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.rag.campus.client.DeepSeekClient;
import com.rag.campus.client.EmbeddingClient;
import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.Conversation;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.ConversationMapper;
import com.rag.campus.mapper.DocumentMapper;
import com.rag.campus.service.RagService;
import com.rag.campus.support.RagPromptTemplate;
import com.rag.campus.support.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * RAG 问答服务实现
 * <p>
 * 核心链路：
 * 用户问题 → 向量化 → 语义检索 → Prompt组装 → LLM生成 → 返回结果
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagServiceImpl implements RagService {

    private final DeepSeekClient deepSeekClient;
    private final EmbeddingClient embeddingClient;
    private final VectorStore vectorStore;
    private final ConversationMapper conversationMapper;
    private final DocumentMapper documentMapper;
    private final StringRedisTemplate redisTemplate;

    @Value("${rag.retrieval.top-k:3}")
    private int topK;

    @Value("${rag.retrieval.min-score:0.55}")
    private double minScore;

    @Value("${rag.conversation.max-history:10}")
    private int maxHistory;

    @Value("${rag.conversation.ttl-hours:24}")
    private int ttlHours;

    /** Redis Key前缀 */
    private static final String CONVERSATION_HISTORY_PREFIX = "rag:conversation:";

    @Override
    public ChatResponse ask(ChatRequest request) {
        String question = request.getQuestion();
        if (StrUtil.isBlank(question)) {
            return ChatResponse.builder()
                    .answer("请输入您的问题。")
                    .sources(Collections.emptyList())
                    .build();
        }

        // 生成或复用 sessionId
        String sessionId = StrUtil.isBlank(request.getSessionId())
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.getSessionId();

        log.info("RAG问答开始: sessionId={}, question={}", sessionId, question);

        try {
            // === Step 1: 查询改写（多轮对话上下文补全） ===
            // 先加载瘦身历史（仅原始Q&A），用于查询改写和后续消息组装
            List<Map<String, String>> slimHistory = loadConversationHistory(sessionId);

            // 如果有历史，对当前追问做改写，补全省略的主语/指代
            String searchQuery = question;
            if (!slimHistory.isEmpty()) {
                String rewritePrompt = RagPromptTemplate.buildRewritePrompt(slimHistory, question);
                String rewritten = deepSeekClient.rewriteQuery(rewritePrompt);
                if (StrUtil.isNotBlank(rewritten) && !rewritten.equals(question)) {
                    log.info("查询改写: {} -> {}", question, rewritten);
                    searchQuery = rewritten;
                }
            }

            // === Step 2: 问题向量化（使用改写后的查询） ===
            float[] questionVector = embeddingClient.embed(searchQuery);
            if (questionVector.length == 0) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer("抱歉，向量化服务暂不可用，请稍后重试。")
                        .sources(Collections.emptyList())
                        .build();
            }

            // === Step 3: 语义检索（先用大窗口召回，再关键词加权，最后截断） ===
            int candidateSize = Math.min(topK * 3, vectorStore.size());
            List<VectorStore.Hit> candidates = vectorStore.search(questionVector, candidateSize);
            if (candidates.isEmpty()) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer("知识库中暂无相关内容。请先上传相关文档后再提问，或尝试换一种问法。")
                        .sources(Collections.emptyList())
                        .build();
            }

            // 关键词加权重排序（向量分 + 关键词命中；使用改写后的查询做关键词提取）
            // 召回 2× topK，为后续文档多样性过滤留空间
            List<VectorStore.Hit> reranked = rerankByKeywordBoost(searchQuery, candidates, topK * 2);

            // 文档标题匹配加分：查询含"材料学院"→材料学院文档的 chunk 加分
            List<VectorStore.Hit> boosted = boostByDocumentTitle(searchQuery, reranked);

            // 文档多样性截断：每个文档最多贡献 3 个 chunk，防止单个文档霸榜
            List<VectorStore.Hit> hits = applyDocumentDiversity(boosted, topK);

            // 低分拦截：过滤相似度不达标的chunk，减少无关参考资料
            hits = filterByMinScore(hits);

            // 缓存文档查询（buildUserPrompt + buildSources 各自遍历 hits，共用缓存避免重复查DB）
            Map<Long, Document> docCache = new HashMap<>();
            java.util.function.Function<Long, Document> cachedDocResolver = id ->
                    docCache.computeIfAbsent(id, this::getDocumentById);

            // === Step 4: 构建 Prompt ===
            // system prompt 每轮新鲜生成，不存 Redis
            String systemPrompt = RagPromptTemplate.buildSystemPrompt();
            // user prompt 携带当前轮的 chunk 上下文 + 用户原始问题
            String userPrompt = RagPromptTemplate.buildUserPrompt(
                    question, hits,
                    vectorStore::getChunk,
                    cachedDocResolver
            );

            // === Step 5: 组装消息列表 ===
            // 结构: [system(fresh)] + [瘦身历史(仅原始Q&A)] + [当前user(含chunk)]
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.addAll(slimHistory);               // 历史只含原始Q&A，无chunk
            messages.add(Map.of("role", "user", "content", userPrompt));

            // === Step 6: 调用 LLM 生成回答 ===
            String answer = deepSeekClient.chatWithHistory(messages);

            // === Step 7: 构建引用来源 ===
            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    hits, vectorStore::getChunk, cachedDocResolver
            );

            // === Step 8: 保存瘦身对话历史到 Redis（仅原始Q&A） ===
            saveConversationHistory(sessionId, slimHistory, question, answer);

            // === Step 9: 持久化到 MySQL ===
            Conversation conversation = new Conversation();
            conversation.setSessionId(sessionId);
            conversation.setQuestion(question);
            conversation.setAnswer(answer);
            conversation.setSources(JSONUtil.toJsonStr(sources));
            conversation.setCreateTime(LocalDateTime.now());
            conversationMapper.insert(conversation);

            log.info("RAG问答完成: sessionId={}, searchQuery={}, hits={}", sessionId, searchQuery, hits.size());

            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer(answer)
                    .sources(sources)
                    .build();

        } catch (Exception e) {
            log.error("RAG问答异常", e);
            return ChatResponse.builder()
                    .sessionId(sessionId)
                    .answer("抱歉，系统处理您的问题时出现异常：" + e.getMessage())
                    .sources(Collections.emptyList())
                    .build();
        }
    }

    // ==================== 对话历史管理（Redis） ====================

    /**
     * 从Redis加载最近N轮对话历史（瘦身格式：仅原始Q&A，不含system prompt和chunk）
     * <p>
     * 兼容处理：如果检测到旧格式（首条消息 role=system），说明是旧版本残留，
     * 直接返回空列表开始新对话，旧 key 会在 TTL 后自然过期。
     */
    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        String key = CONVERSATION_HISTORY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }

        try {
            List<Map<String, String>> history = JSONUtil.toList(json, Map.class)
                    .stream()
                    .map(m -> (Map<String, String>) m)
                    .collect(java.util.stream.Collectors.toList());

            // 兼容旧格式：旧版本首条是 system prompt，检测到则丢弃，开始新对话
            if (!history.isEmpty() && "system".equals(history.get(0).get("role"))) {
                log.info("检测到旧格式对话历史(sessionId={})，将开始新对话", sessionId);
                redisTemplate.delete(key);
                return new ArrayList<>();
            }

            // 只保留最近 maxHistory 轮（一轮 = user + assistant 两条）
            int maxMessages = maxHistory * 2;
            if (history.size() > maxMessages) {
                return history.subList(history.size() - maxMessages, history.size());
            }
            return history;
        } catch (Exception e) {
            log.warn("解析对话历史失败，将开始新对话", e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存本轮对话到Redis（瘦身格式：仅原始Q&A，不含system prompt和chunk）
     * <p>
     * 存储格式: [{"role":"user","content":"原始问题"}, {"role":"assistant","content":"回答"}, ...]
     * 不再存储 system prompt 和带 chunk 的 RAG user prompt，避免历史膨胀和旧 chunk 干扰。
     */
    private void saveConversationHistory(String sessionId,
                                         List<Map<String, String>> slimHistory,
                                         String question,
                                         String answer) {
        // 追加本轮原始 Q&A（不含 chunk）
        List<Map<String, String>> toSave = new ArrayList<>(slimHistory);
        toSave.add(Map.of("role", "user", "content", question));
        toSave.add(Map.of("role", "assistant", "content", answer));

        String key = CONVERSATION_HISTORY_PREFIX + sessionId;
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(toSave), ttlHours, TimeUnit.HOURS);
    }

    /**
     * 桥接方法：将 MyBatis-Plus BaseMapper.selectById(Serializable) 适配为 Function&lt;Long, Document&gt;
     */
    private Document getDocumentById(Long id) {
        return documentMapper.selectById(id);
    }

    /**
     * 关键词加权重排序
     * <p>
     * 纯向量检索有时会把"高分但语义有偏差"的chunk排在前面。
     * 此方法对候选chunk做关键词匹配加分，使向量相似度+关键词双重命中
     * 的chunk排到更前面。
     * <p>
     * 示例：查询"考试违纪的认定及处理"
     *   - 关键词: 考试, 违纪, 认定, 处理
     *   - chunk 若含"违纪"→+0.15, 含"认定"→再+0.05，以此类推
     *
     * @param query      用户原始查询
     * @param candidates 向量检索的较大候选集
     * @param topK       最终返回数量
     */
    private List<VectorStore.Hit> rerankByKeywordBoost(String query,
                                                        List<VectorStore.Hit> candidates,
                                                        int topK) {
        // 1. 提取查询中的关键词（长度≥2的中文词）
        List<String> keywords = extractKeywords(query);

        if (keywords.isEmpty() || candidates.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        // 2. 对每个候选chunk计算关键词加分
        List<VectorStore.Hit> boosted = new ArrayList<>();
        for (VectorStore.Hit hit : candidates) {
            DocumentChunk chunk = vectorStore.getChunk(hit.getChunkId());
            if (chunk == null) continue;

            String chunkText = chunk.getChunkText();
            if (chunkText == null) {
                boosted.add(hit);
                continue;
            }

            // 计算关键词命中率（去重命中）
            int hitCount = 0;
            for (String kw : keywords) {
                if (chunkText.contains(kw)) {
                    hitCount++;
                }
            }
            double keywordRatio = (double) hitCount / keywords.size();

            // 向量分 + 关键词加分（最多 +0.2）
            float boostedScore = (float) (hit.getScore() + 0.2 * keywordRatio);
            boosted.add(new VectorStore.Hit(hit.getChunkId(), boostedScore));
        }

        // 3. 按加分后的分数降序排列，取 topK
        boosted.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return boosted.subList(0, Math.min(topK, boosted.size()));
    }

    /**
     * 从查询中提取关键词
     * 策略：按常见分隔符切分后，保留长度≥2的中文字符段
     */
    private List<String> extractKeywords(String query) {
        if (StrUtil.isBlank(query)) return Collections.emptyList();

        // 按标点/空格切分
        String[] parts = query.split("[，。？?！!、；;：:\\s]+");
        List<String> keywords = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            // 过滤单字和无意义词
            if (trimmed.length() >= 2
                    && !trimmed.equals("请问")
                    && !trimmed.equals("一下")
                    && !trimmed.equals("什么")
                    && !trimmed.equals("如何")
                    && !trimmed.equals("怎么")
                    && !trimmed.equals("吗")
                    && !trimmed.equals("呢")
                    && !trimmed.equals("的")) {
                keywords.add(trimmed);
            }
        }
        return keywords;
    }

    /**
     * 低分拦截：过滤相似度不达标的 chunk
     * <p>
     * 无关文档的 chunk 可能因词汇交集获得一定向量分（如 0.3~0.45），
     * 但实际内容不相关。0.4 的阈值可有效过滤这类"虚高"结果。
     */
    private List<VectorStore.Hit> filterByMinScore(List<VectorStore.Hit> hits) {
        if (hits.isEmpty()) return hits;
        List<VectorStore.Hit> filtered = new ArrayList<>();
        for (VectorStore.Hit hit : hits) {
            if (hit.getScore() >= minScore) {
                filtered.add(hit);
            }
        }
        if (filtered.isEmpty()) {
            // 全部被过滤时退回原始结果（避免无结果）
            log.warn("所有chunk均低于最低分阈值({}), 退回原始结果", minScore);
            return hits;
        }
        log.debug("低分过滤: {} → {} 个chunk (threshold={})", hits.size(), filtered.size(), minScore);
        return filtered;
    }

    // ==================== 文档归属重排序 ====================

    /**
     * 从查询中提取机构/实体名称，用于文档标题匹配
     * <p>
     * 匹配模式: XX学院、XX系、XX部、XX处、XX中心、XX办公室 等常见校园机构名。
     * 未匹配到时返回空列表，后续不加分。
     */
    private static final Pattern ENTITY_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fa5]{2,6}(?:学院|大学|系|部|处|中心|办公室|委员会|基地|实验室|研究所|教研室)"
    );

    private List<String> extractEntityNames(String query) {
        if (StrUtil.isBlank(query)) return Collections.emptyList();
        List<String> entities = new ArrayList<>();
        Matcher m = ENTITY_PATTERN.matcher(query);
        while (m.find()) {
            entities.add(m.group());
        }
        return entities;
    }

    /**
     * 文档标题匹配加分
     * <p>
     * 问题："材料学院综测和软件学院一样吗" → 提取实体: [材料学院, 软件学院]
     * → 材料学院综测.pdf 的 chunk +0.15，软件学院综测.pdf 的 chunk +0.10
     * → 两个文档的 chunk 都排在前面，不相关的文档自然落后
     * <p>
     * 加分策略：第一个实体匹配 +0.15，后续实体逐次递减（语义上用户最先提到的通常最重要）
     */
    private List<VectorStore.Hit> boostByDocumentTitle(String query,
                                                       List<VectorStore.Hit> candidates) {
        List<String> entities = extractEntityNames(query);
        if (entities.isEmpty() || candidates.isEmpty()) return candidates;

        // 预加载所有 documentId → title 映射（避免重复查 DB）
        Map<Long, String> titleCache = new HashMap<>();
        for (VectorStore.Hit hit : candidates) {
            Long docId = getDocId(hit);
            if (docId != null && !titleCache.containsKey(docId)) {
                Document doc = getDocumentById(docId);
                if (doc != null) {
                    titleCache.put(docId, doc.getTitle());
                }
            }
        }

        List<VectorStore.Hit> boosted = new ArrayList<>();
        for (VectorStore.Hit hit : candidates) {
            Long docId = getDocId(hit);
            float bonus = 0f;

            if (docId != null) {
                String title = titleCache.get(docId);
                if (title != null) {
                    // 按实体顺序加权：第一个匹配的实体加分最高
                    for (int i = 0; i < entities.size(); i++) {
                        if (title.contains(entities.get(i))) {
                            bonus = Math.max(bonus, 0.15f - i * 0.05f);
                        }
                    }
                }
            }
            boosted.add(new VectorStore.Hit(hit.getChunkId(), hit.getScore() + bonus));
        }

        boosted.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return boosted;
    }

    /**
     * 文档多样性截断
     * <p>
     * 每个文档最多贡献 maxPerDoc 个 chunk，超出的丢弃，
     * 用其他文档的 chunk 补位，确保检索结果覆盖多个文档。
     * <p>
     * 示例: 材料学院 chunk1-8（高分）→ 只保留 top 3，
     * 第 4-8 个位置由其他文档（软件学院等）的 chunk 补充
     */
    private List<VectorStore.Hit> applyDocumentDiversity(List<VectorStore.Hit> candidates,
                                                        int targetCount) {
        final int maxPerDoc = 3;

        List<VectorStore.Hit> result = new ArrayList<>();
        List<VectorStore.Hit> overflow = new ArrayList<>();
        Map<Long, Integer> docCounts = new HashMap<>();

        for (VectorStore.Hit hit : candidates) {
            Long docId = getDocId(hit);
            if (docId == null) {
                if (result.size() < targetCount) result.add(hit);
                continue;
            }

            int count = docCounts.getOrDefault(docId, 0);
            if (count < maxPerDoc) {
                result.add(hit);
                docCounts.put(docId, count + 1);
            } else {
                overflow.add(hit);
            }
        }

        // 如果结果不足 targetCount，用 overflow 补齐
        for (VectorStore.Hit hit : overflow) {
            if (result.size() >= targetCount) break;
            result.add(hit);
        }

        return result;
    }

    /**
     * 从 Hit 获取所属文档 ID
     */
    private Long getDocId(VectorStore.Hit hit) {
        DocumentChunk chunk = vectorStore.getChunk(hit.getChunkId());
        return chunk != null ? chunk.getDocumentId() : null;
    }
}
