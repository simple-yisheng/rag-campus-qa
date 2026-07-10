package com.rag.campus.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.rag.campus.client.DeepSeekClient;
import com.rag.campus.client.EmbeddingClient;
import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.campus.entity.ConversationMessage;
import com.rag.campus.entity.ConversationSession;
import com.rag.campus.entity.Document;
import com.rag.campus.entity.DocumentChunk;
import com.rag.campus.mapper.ConversationMessageMapper;
import com.rag.campus.mapper.ConversationSessionMapper;
import com.rag.campus.mapper.DocumentMapper;
import com.rag.campus.service.RagService;
import com.rag.campus.service.UserService;
import com.rag.campus.support.RagPromptTemplate;
import com.rag.campus.support.VectorStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final ConversationSessionMapper sessionMapper;
    private final ConversationMessageMapper messageMapper;
    private final DocumentMapper documentMapper;
    private final StringRedisTemplate redisTemplate;
    private final UserService userService;

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
    private static final String HISTORY_CACHE_PREFIX = "rag:history:";
    private static final int SOURCE_LIMIT = 5;
    private static final double RELATIVE_SCORE_RATIO = 0.8;
    private static final double KEYWORD_BOOST_MAX = 0.35;
    private static final float ZERO_KEYWORD_PENALTY = -0.12f;

    private static final Set<String> DOMAIN_TERMS = Set.of(
            "考试", "考试管理", "考试管理办法", "考场", "证件", "学生证", "一卡通", "监考", "违纪", "作弊",
            "缓考", "补考", "重修", "成绩", "英语摸底", "分级考试", "英语分级", "听力", "耳机",
            "综测", "综合测评", "综合素质测评", "素质测评", "奖学金", "评优", "评奖评优", "德育", "智育", "体育",
            "竞赛", "学科竞赛", "竞赛项目", "项目名录", "竞赛名录", "认定竞赛", "赛事", "比赛",
            "转专业", "保研", "推免", "学籍", "休学", "复学", "退学", "毕业", "学位"
    );

    private static final Set<String> ASSESSMENT_TITLE_TERMS = Set.of(
            "综测", "综合测评", "综合素质测评", "素质测评", "奖学金", "评优", "评奖评优"
    );

    private static final Set<String> EXAM_INTENT_TERMS = Set.of(
            "考试", "考试管理", "考场", "证件", "监考", "违纪", "作弊", "缓考", "补考", "英语摸底", "分级考试"
    );

    private static final Set<String> COMPETITION_INTENT_TERMS = Set.of(
            "竞赛", "学科竞赛", "竞赛项目", "项目名录", "竞赛名录", "认定竞赛", "赛事", "比赛"
    );

    private static final Set<String> CATALOG_TITLE_TERMS = Set.of(
            "名录", "目录", "清单", "项目名录", "竞赛项目", "认定"
    );

    private static final Set<String> LIST_QUERY_TERMS = Set.of(
            "哪些", "有哪些", "有什么", "名录", "目录", "清单", "列表"
    );

    private static final Set<String> STOPWORDS = Set.of(
            "请问", "一下", "什么", "如何", "怎么", "吗", "呢", "的", "了", "是", "在", "和", "与", "或",
            "有", "我", "你", "他", "这", "那", "哪", "吧", "啊", "要", "需要", "注意", "可以", "是否",
            "想问", "想知道", "帮我查", "问一下"
    );

    private static final Map<String, Set<String>> SYNONYM_TERMS = Map.ofEntries(
            Map.entry("综测", Set.of("综测", "综合测评", "综合素质测评", "素质测评")),
            Map.entry("综合测评", Set.of("综合测评", "综测", "综合素质测评", "素质测评")),
            Map.entry("综合素质测评", Set.of("综合素质测评", "综合测评", "素质测评", "综测")),
            Map.entry("素质测评", Set.of("素质测评", "综合测评", "综合素质测评", "综测")),
            Map.entry("评奖评优", Set.of("评奖评优", "评优", "奖学金", "荣誉称号")),
            Map.entry("评优", Set.of("评优", "评奖评优", "奖学金", "荣誉称号")),
            Map.entry("奖学金", Set.of("奖学金", "评奖评优", "评优")),
            Map.entry("竞赛", Set.of("竞赛", "学科竞赛", "竞赛项目", "项目名录", "竞赛名录", "赛事", "比赛")),
            Map.entry("学科竞赛", Set.of("学科竞赛", "竞赛", "竞赛项目", "项目名录", "竞赛名录")),
            Map.entry("竞赛项目", Set.of("竞赛项目", "学科竞赛", "竞赛", "项目名录", "竞赛名录")),
            Map.entry("项目名录", Set.of("项目名录", "竞赛项目", "竞赛名录", "学科竞赛", "竞赛")),
            Map.entry("竞赛名录", Set.of("竞赛名录", "项目名录", "竞赛项目", "学科竞赛", "竞赛")),
            Map.entry("赛事", Set.of("赛事", "比赛", "竞赛", "学科竞赛")),
            Map.entry("比赛", Set.of("比赛", "赛事", "竞赛", "学科竞赛")),
            Map.entry("考试", Set.of("考试", "考核", "测验")),
            Map.entry("英语摸底", Set.of("英语摸底", "英语分级", "分级考试")),
            Map.entry("分级考试", Set.of("分级考试", "英语分级", "英语摸底")),
            Map.entry("违纪", Set.of("违纪", "作弊", "违规")),
            Map.entry("作弊", Set.of("作弊", "违纪", "违规"))
    );

    @Override
    public ChatResponse ask(ChatRequest request) {
        String question = request.getQuestion();
        if (StrUtil.isBlank(question)) {
            return ChatResponse.builder()
                    .answer("请输入您的问题。")
                    .sources(Collections.emptyList())
                    .build();
        }

        // 生成或复用 sessionId（8位字母数字，首次提问由后端生成）
        String sessionId = StrUtil.isBlank(request.getSessionId())
                ? generateSessionId()
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

            // === Step 3: 语义检索 + 重排过滤 ===
            List<VectorStore.Hit> hits = retrieveRelevantHits(searchQuery, questionVector);
            if (hits.isEmpty()) {
                return ChatResponse.builder()
                        .sessionId(sessionId)
                        .answer("知识库中暂无相关内容。请先上传相关文档后再提问，或尝试换一种问法。")
                        .sources(Collections.emptyList())
                        .build();
            }

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
                    hits, vectorStore::getChunk, cachedDocResolver, SOURCE_LIMIT
            );

            // === Step 8: 保存瘦身对话历史到 Redis（仅原始Q&A） ===
            saveConversationHistory(sessionId, slimHistory, question, answer);

            // === Step 9: 持久化到 MySQL ===
            com.rag.campus.entity.User currentUser = userService.getCurrentUser();
            Long userId = currentUser != null ? currentUser.getId() : null;

            // 首次对话时创建 session
            if (slimHistory.isEmpty()) {
                ConversationSession session = new ConversationSession();
                session.setSessionId(sessionId);
                session.setUserId(userId);
                session.setTitle(question.length() > 50 ? question.substring(0, 50) + "..." : question);
                session.setCreateTime(LocalDateTime.now());
                session.setLastActiveTime(LocalDateTime.now());
                sessionMapper.insert(session);
                // 新建会话 → 清掉用户的 sessions 缓存
                if (userId != null) {
                    redisTemplate.delete("rag:sessions:user:" + userId);
                }
            } else {
                // 更新最后活跃时间（先查再改，避免 deprecation warning）
                ConversationSession existSession = sessionMapper.selectOne(
                        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ConversationSession>()
                                .eq(ConversationSession::getSessionId, sessionId));
                if (existSession != null) {
                    existSession.setLastActiveTime(LocalDateTime.now());
                    sessionMapper.updateById(existSession);
                    // 活跃时间变了 → 清掉 sessions 缓存（排序可能变化）
                    Long suid = existSession.getUserId();
                    if (suid != null) {
                        redisTemplate.delete("rag:sessions:user:" + suid);
                    }
                }
            }

            // 保存消息
            ConversationMessage message = new ConversationMessage();
            message.setSessionId(sessionId);
            message.setQuestion(question);
            message.setAnswer(answer);
            message.setSources(JSONUtil.toJsonStr(sources));
            message.setCreateTime(LocalDateTime.now());
            messageMapper.insert(message);

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

    @Override
    public void askStream(ChatRequest request, SseEmitter emitter) {
        String question = request.getQuestion();
        if (StrUtil.isBlank(question)) {
            sendSseError(emitter, "请输入您的问题。");
            return;
        }

        String sessionId = StrUtil.isBlank(request.getSessionId())
                ? generateSessionId()
                : request.getSessionId();

        log.info("RAG流式问答开始: sessionId={}, question={}", sessionId, question);

        try {
            // === Step 1-5: 检索增强（与同步 ask 完全相同） ===
            List<Map<String, String>> slimHistory = loadConversationHistory(sessionId);

            String searchQuery = question;
            if (!slimHistory.isEmpty()) {
                String rewritePrompt = RagPromptTemplate.buildRewritePrompt(slimHistory, question);
                String rewritten = deepSeekClient.rewriteQuery(rewritePrompt);
                if (StrUtil.isNotBlank(rewritten) && !rewritten.equals(question)) {
                    log.info("查询改写: {} -> {}", question, rewritten);
                    searchQuery = rewritten;
                }
            }

            float[] questionVector = embeddingClient.embed(searchQuery);
            if (questionVector.length == 0) {
                sendSseError(emitter, "向量化服务暂不可用");
                return;
            }

            List<VectorStore.Hit> hits = retrieveRelevantHits(searchQuery, questionVector);
            if (hits.isEmpty()) {
                sendSseError(emitter, "知识库中暂无相关内容");
                return;
            }

            Map<Long, Document> docCache = new HashMap<>();
            java.util.function.Function<Long, Document> cachedDocResolver = id ->
                    docCache.computeIfAbsent(id, this::getDocumentById);

            String systemPrompt = RagPromptTemplate.buildSystemPrompt();
            String userPrompt = RagPromptTemplate.buildUserPrompt(
                    question, hits, vectorStore::getChunk, cachedDocResolver);

            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.addAll(slimHistory);
            messages.add(Map.of("role", "user", "content", userPrompt));

            // === Step 6: 流式 LLM 生成 ===
            List<ChatResponse.SourceInfo> sources = RagPromptTemplate.buildSources(
                    hits, vectorStore::getChunk, cachedDocResolver, SOURCE_LIMIT);

            deepSeekClient.chatStream(messages,
                    // onToken：逐字推送给前端
                    token -> {
                        try {
                            Map<String, Object> event = new HashMap<>();
                            event.put("token", token);
                            emitter.send(SseEmitter.event()
                                    .data(event, MediaType.APPLICATION_JSON));
                        } catch (Exception e) {
                            log.debug("SSE 发送 token 失败", e);
                        }
                    },
                    // onComplete：推送来源 + 完成信号，保存记录
                    answer -> {
                        try {
                            // 发送来源
                            Map<String, Object> doneEvent = new HashMap<>();
                            doneEvent.put("sessionId", sessionId);
                            doneEvent.put("sources", sources);
                            emitter.send(SseEmitter.event()
                                    .name("sources")
                                    .data(doneEvent, MediaType.APPLICATION_JSON));

                            // 发送完成信号
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data(""));

                            // 持久化
                            saveConversationHistory(sessionId, slimHistory, question, answer);
                            persistConversation(sessionId, slimHistory, question, answer, sources);

                            emitter.complete();
                        } catch (Exception e) {
                            log.error("SSE 完成发送失败", e);
                            emitter.completeWithError(e);
                        }
                    },
                    // onError
                    errorMsg -> sendSseError(emitter, errorMsg)
            );

        } catch (Exception e) {
            log.error("RAG流式问答异常", e);
            sendSseError(emitter, "系统异常: " + e.getMessage());
        }
    }

    private void sendSseError(SseEmitter emitter, String msg) {
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("error", msg);
            emitter.send(SseEmitter.event()
                    .name("error")
                    .data(event, MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (Exception ex) {
            emitter.completeWithError(ex);
        }
    }

    /**
     * 持久化对话记录（提取自 ask() 供 askStream 复用）
     */
    private void persistConversation(String sessionId, List<Map<String, String>> slimHistory,
                                     String question, String answer, List<ChatResponse.SourceInfo> sources) {
        com.rag.campus.entity.User currentUser = userService.getCurrentUser();
        Long userId = currentUser != null ? currentUser.getId() : null;

        if (slimHistory.isEmpty()) {
            ConversationSession session = new ConversationSession();
            session.setSessionId(sessionId);
            session.setUserId(userId);
            session.setTitle(question.length() > 50 ? question.substring(0, 50) + "..." : question);
            session.setCreateTime(LocalDateTime.now());
            session.setLastActiveTime(LocalDateTime.now());
            sessionMapper.insert(session);
            if (userId != null) {
                redisTemplate.delete("rag:sessions:user:" + userId);
            }
        } else {
            ConversationSession existSession = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ConversationSession>()
                            .eq(ConversationSession::getSessionId, sessionId));
            if (existSession != null) {
                existSession.setLastActiveTime(LocalDateTime.now());
                sessionMapper.updateById(existSession);
                Long suid = existSession.getUserId();
                if (suid != null) {
                    redisTemplate.delete("rag:sessions:user:" + suid);
                }
            }
        }

        ConversationMessage message = new ConversationMessage();
        message.setSessionId(sessionId);
        message.setQuestion(question);
        message.setAnswer(answer);
        message.setSources(JSONUtil.toJsonStr(sources));
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);
        redisTemplate.delete(HISTORY_CACHE_PREFIX + sessionId);
    }

    // ==================== 对话历史管理（Redis 缓存 + MySQL 兜底） ====================

    /** 从 MySQL 加载 N 轮并回填 Redis，返回对 LLM 友好的瘦身格式 */
    private List<Map<String, String>> loadConversationHistory(String sessionId) {
        String key = CONVERSATION_HISTORY_PREFIX + sessionId;

        // 1. 尝试 Redis 缓存
        String json = redisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            try {
                List<Map<String, String>> history = JSONUtil.toList(json, Map.class)
                        .stream()
                        .map(m -> (Map<String, String>) m)
                        .collect(java.util.stream.Collectors.toList());
                // 兼容旧格式
                if (!history.isEmpty() && "system".equals(history.get(0).get("role"))) {
                    log.info("检测到旧格式对话历史(sessionId={})，清除并重建", sessionId);
                    redisTemplate.delete(key);
                } else {
                    return trimHistory(history);
                }
            } catch (Exception e) {
                log.warn("Redis 解析失败，回退到 MySQL", e);
            }
        }

        // 2. Redis miss → 查 MySQL 回填
        List<ConversationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getSessionId, sessionId)
                        .orderByAsc(ConversationMessage::getCreateTime)
        );

        if (messages.isEmpty()) {
            return new ArrayList<>();
        }

        // 3. 转成瘦身格式 [{"role":"user","content":"..."}, ...]
        List<Map<String, String>> history = new ArrayList<>();
        for (ConversationMessage msg : messages) {
            history.add(Map.of("role", "user", "content",
                    StrUtil.blankToDefault(msg.getQuestion(), "")));
            history.add(Map.of("role", "assistant", "content",
                    StrUtil.blankToDefault(msg.getAnswer(), "")));
        }

        // 4. 回填 Redis（短期 TTL，作为热缓存）
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(history), ttlHours, TimeUnit.HOURS);

        return trimHistory(history);
    }

    /** 只保留最近 N 轮 */
    private List<Map<String, String>> trimHistory(List<Map<String, String>> history) {
        int maxMessages = maxHistory * 2;
        if (history.size() > maxMessages) {
            return history.subList(history.size() - maxMessages, history.size());
        }
        return history;
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
     * 统一检索链路：召回 → 关键词重排 → 标题/意图重排 → 阈值过滤 → 文档多样性截断。
     */
    private List<VectorStore.Hit> retrieveRelevantHits(String searchQuery, float[] questionVector) {
        int candidateSize = Math.min(topK * 3, vectorStore.size());
        if (candidateSize <= 0) {
            return Collections.emptyList();
        }

        List<VectorStore.Hit> candidates = vectorStore.search(questionVector, candidateSize);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<QueryTerm> terms = extractQueryTerms(searchQuery);
        List<VectorStore.Hit> reranked = rerankByKeywordBoost(candidates, terms, candidates.size());
        List<VectorStore.Hit> titleBoosted = boostByDocumentTitle(searchQuery, terms, reranked);
        List<VectorStore.Hit> filtered = filterByMinScore(titleBoosted);
        filtered = filterByRelativeScore(filtered);
        return applyDocumentDiversity(filtered, topK);
    }

    /**
     * 关键词加权重排序 + 条件式零命中惩罚。
     */
    private List<VectorStore.Hit> rerankByKeywordBoost(List<VectorStore.Hit> candidates,
                                                       List<QueryTerm> terms,
                                                       int topK) {
        if (terms.isEmpty() || candidates.isEmpty()) {
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }

        double totalWeight = terms.stream().mapToDouble(QueryTerm::weight).sum();
        boolean enableZeroPenalty = terms.stream().anyMatch(QueryTerm::strong);

        List<VectorStore.Hit> boosted = new ArrayList<>();
        for (VectorStore.Hit hit : candidates) {
            DocumentChunk chunk = vectorStore.getChunk(hit.getChunkId());
            if (chunk == null) continue;

            String chunkText = chunk.getChunkText();
            if (chunkText == null) {
                boosted.add(hit);
                continue;
            }

            double matchedWeight = 0;
            for (QueryTerm term : terms) {
                if (chunkText.contains(term.term())) {
                    matchedWeight += term.weight();
                }
            }

            float boost = 0f;
            if (matchedWeight <= 0 && enableZeroPenalty) {
                boost = ZERO_KEYWORD_PENALTY;
            } else if (totalWeight > 0) {
                boost = (float) (KEYWORD_BOOST_MAX * (matchedWeight / totalWeight));
            }

            boosted.add(new VectorStore.Hit(hit.getChunkId(), hit.getScore() + boost));
        }

        boosted.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return boosted.subList(0, Math.min(topK, boosted.size()));
    }

    /**
     * 提取带权重的查询词。
     * <p>
     * 不做全量字符 bigram，避免生成"试管""理办"这类跨语义边界噪声。
     */
    private List<QueryTerm> extractQueryTerms(String query) {
        if (StrUtil.isBlank(query)) return Collections.emptyList();

        String cleaned = query.replaceAll("^(请问|我想问|我想知道|帮我查|问一下|请问一下)", "").trim();
        Map<String, QueryTerm> terms = new LinkedHashMap<>();

        // 领域词优先，权重最高。
        DOMAIN_TERMS.stream()
                .sorted((a, b) -> Integer.compare(b.length(), a.length()))
                .filter(cleaned::contains)
                .forEach(term -> putTermWithSynonyms(terms, term, 2.0, true));

        String normalized = cleaned.replaceAll("(以及|有关|关于|需要|注意|什么|如何|怎么|哪些|有什么|怎么办|的|及|和|与|或|要|吗|呢|了|是|在)", " ");
        String[] parts = normalized.split("[，。？?！!、；;：:\\s]+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!isUsefulTerm(trimmed)) continue;

            putTermWithSynonyms(terms, trimmed, 1.5, trimmed.length() >= 4);
            addBoundaryTerms(terms, trimmed);
        }

        return new ArrayList<>(terms.values());
    }

    private void addBoundaryTerms(Map<String, QueryTerm> terms, String phrase) {
        if (phrase.length() < 4) return;

        putTerm(terms, phrase.substring(0, 2), 0.8, false);
        putTerm(terms, phrase.substring(phrase.length() - 2), 0.8, false);

        if (phrase.length() >= 5) {
            putTerm(terms, phrase.substring(0, 4), 1.0, false);
            putTerm(terms, phrase.substring(phrase.length() - 4), 1.0, false);
        }
    }

    private void putTerm(Map<String, QueryTerm> terms, String term, double weight, boolean strong) {
        if (!isUsefulTerm(term)) return;

        QueryTerm existing = terms.get(term);
        if (existing == null || weight > existing.weight() || (strong && !existing.strong())) {
            terms.put(term, new QueryTerm(term, weight, strong || (existing != null && existing.strong())));
        }
    }

    private void putTermWithSynonyms(Map<String, QueryTerm> terms, String term, double weight, boolean strong) {
        putTerm(terms, term, weight, strong);

        Set<String> synonyms = SYNONYM_TERMS.get(term);
        if (synonyms == null) return;

        for (String synonym : synonyms) {
            putTerm(terms, synonym, weight, true);
        }
    }

    private boolean isUsefulTerm(String term) {
        if (StrUtil.isBlank(term) || term.length() < 2 || STOPWORDS.contains(term)) {
            return false;
        }
        return term.chars().anyMatch(ch -> Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN);
    }

    /** 兼容少量旧调用语义：只返回词面。 */
    private List<String> extractKeywords(String query) {
        return extractQueryTerms(query).stream().map(QueryTerm::term).toList();
    }

    private record QueryTerm(String term, double weight, boolean strong) {}

    /**
     * 低分拦截：过滤综合相关度不达标的 chunk。
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
            log.warn("所有chunk均低于最低分阈值({}), 保留最高分chunk作为兜底", minScore);
            return hits.subList(0, 1);
        }
        log.debug("低分过滤: {} → {} 个chunk (threshold={})", hits.size(), filtered.size(), minScore);
        return filtered;
    }

    /**
     * 相对阈值过滤：弱相关 chunk 即便超过绝对阈值，也不能距离最高分太远。
     */
    private List<VectorStore.Hit> filterByRelativeScore(List<VectorStore.Hit> hits) {
        if (hits.isEmpty()) return hits;
        float topScore = hits.get(0).getScore();
        double threshold = Math.max(minScore, topScore * RELATIVE_SCORE_RATIO);

        List<VectorStore.Hit> filtered = new ArrayList<>();
        for (VectorStore.Hit hit : hits) {
            if (hit.getScore() >= threshold) {
                filtered.add(hit);
            }
        }

        if (filtered.isEmpty()) {
            return hits.subList(0, 1);
        }
        log.debug("相对分过滤: {} → {} 个chunk (threshold={})", hits.size(), filtered.size(), threshold);
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
                                                       List<QueryTerm> terms,
                                                       List<VectorStore.Hit> candidates) {
        List<String> entities = extractEntityNames(query);
        if (candidates.isEmpty()) return candidates;

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
                bonus = calculateTitleBonus(query, terms, entities, title);
            }
            boosted.add(new VectorStore.Hit(hit.getChunkId(), hit.getScore() + bonus));
        }

        boosted.sort((a, b) -> Float.compare(b.getScore(), a.getScore()));
        return boosted;
    }

    private float calculateTitleBonus(String query,
                                      List<QueryTerm> terms,
                                      List<String> entities,
                                      String title) {
        if (StrUtil.isBlank(title)) return 0f;

        double totalWeight = terms.stream().mapToDouble(QueryTerm::weight).sum();
        double matchedWeight = 0;
        for (QueryTerm term : terms) {
            if (title.contains(term.term())) {
                matchedWeight += term.weight();
            }
        }

        float bonus = 0f;
        if (totalWeight > 0 && matchedWeight > 0) {
            bonus += (float) (0.25 * (matchedWeight / totalWeight));
        }

        for (int i = 0; i < entities.size(); i++) {
            if (title.contains(entities.get(i))) {
                bonus = Math.max(bonus, 0.15f - i * 0.05f);
            }
        }

        boolean examIntent = containsAny(query, EXAM_INTENT_TERMS) || terms.stream()
                .map(QueryTerm::term)
                .anyMatch(EXAM_INTENT_TERMS::contains);
        boolean assessmentIntent = containsAny(query, ASSESSMENT_TITLE_TERMS);
        if (examIntent && !assessmentIntent && containsAny(title, ASSESSMENT_TITLE_TERMS)) {
            bonus -= 0.18f;
        }

        boolean competitionIntent = containsAny(query, COMPETITION_INTENT_TERMS) || terms.stream()
                .map(QueryTerm::term)
                .anyMatch(COMPETITION_INTENT_TERMS::contains);
        boolean listIntent = containsAny(query, LIST_QUERY_TERMS);
        if (competitionIntent && listIntent && containsAny(title, CATALOG_TITLE_TERMS)) {
            bonus += 0.22f;
        }
        if (competitionIntent && title.toUpperCase(Locale.ROOT).contains("FAQ")) {
            bonus -= 0.08f;
        }

        return bonus;
    }

    private boolean containsAny(String text, Set<String> terms) {
        if (StrUtil.isBlank(text)) return false;
        for (String term : terms) {
            if (text.contains(term)) {
                return true;
            }
        }
        return false;
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

    /** 生成 8 位字母数字对话 ID */
    private static final String ID_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789";
    private String generateSessionId() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(ID_CHARS.charAt((int) (Math.random() * ID_CHARS.length())));
        }
        return sb.toString();
    }
}
