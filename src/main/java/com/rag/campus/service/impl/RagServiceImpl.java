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
            // === Step 1: 问题向量化 ===
            float[] questionVector = embeddingClient.embed(question);
            if (questionVector.length == 0) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer("抱歉，向量化服务暂不可用，请稍后重试。")
                        .sources(Collections.emptyList())
                        .build();
            }

            // === Step 2: 语义检索（先用大窗口召回，再关键词加权，最后截断） ===
            int candidateSize = Math.min(topK * 3, vectorStore.size());
            List<VectorStore.Hit> candidates = vectorStore.search(questionVector, candidateSize);
            if (candidates.isEmpty()) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer("知识库中暂无相关内容。请先上传相关文档后再提问，或尝试换一种问法。")
                        .sources(Collections.emptyList())
                        .build();
            }

            // 关键词加权重排序（向量分 + 关键词命中）
            List<VectorStore.Hit> hits = rerankByKeywordBoost(question, candidates, topK);

            // 缓存文档查询（buildUserPrompt + buildSources 各自遍历 hits，共用缓存避免重复查DB）
            Map<Long, Document> docCache = new HashMap<>();
            java.util.function.Function<Long, Document> cachedDocResolver = id ->
                    docCache.computeIfAbsent(id, this::getDocumentById);

            // === Step 3: 构建 Prompt ===
            String systemPrompt = RagPromptTemplate.buildSystemPrompt();
            String userPrompt = RagPromptTemplate.buildUserPrompt(
                    question, hits,
                    vectorStore::getChunk,
                    cachedDocResolver
            );

            // === Step 4: 加载对话历史（多轮对话） ===
            List<Map<String, String>> messages = loadConversationHistory(sessionId);
            if (messages.isEmpty()) {
                messages.add(Map.of("role", "system", "content", systemPrompt));
            }
            messages.add(Map.of("role", "user", "content", userPrompt));

            // === Step 5: 调用 LLM 生成回答 ===
            String answer = deepSeekClient.chatWithHistory(messages);

            // === Step 6: 构建引用来源 ===
            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    hits, vectorStore::getChunk, cachedDocResolver
            );

            // === Step 7: 保存对话历史到 Redis ===
            saveConversationHistory(sessionId, messages, answer);

            // === Step 8: 持久化到 MySQL ===
            Conversation conversation = new Conversation();
            conversation.setSessionId(sessionId);
            conversation.setQuestion(question);
            conversation.setAnswer(answer);
            conversation.setSources(JSONUtil.toJsonStr(sources));
            conversation.setCreateTime(LocalDateTime.now());
            conversationMapper.insert(conversation);

            log.info("RAG问答完成: sessionId={}, hits={}", sessionId, hits.size());

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
     * 从Redis加载最近N轮对话历史
     */
    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        String key = CONVERSATION_HISTORY_PREFIX + sessionId;
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return new ArrayList<>();
        }

        try {
            // 存储格式: [{"role":"user","content":"..."}, {"role":"assistant","content":"..."}, ...]
            List<Map<String, String>> history = JSONUtil.toList(json, Map.class)
                    .stream()
                    .map(m -> (Map<String, String>) m)
                    .collect(java.util.stream.Collectors.toList());

            // 只保留最近 maxHistory 轮（一轮 = user + assistant）
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
     * 保存本轮对话到Redis
     */
    private void saveConversationHistory(String sessionId,
                                         List<Map<String, String>> messages,
                                         String answer) {
        // 追加本轮 user 和 assistant
        List<Map<String, String>> toSave = new ArrayList<>(messages);
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
}
