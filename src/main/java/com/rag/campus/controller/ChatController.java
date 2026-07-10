package com.rag.campus.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.campus.common.Result;
import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.ConversationMessage;
import com.rag.campus.entity.ConversationSession;
import com.rag.campus.entity.User;
import com.rag.campus.mapper.ConversationMessageMapper;
import com.rag.campus.mapper.ConversationSessionMapper;
import com.rag.campus.service.RagService;
import com.rag.campus.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 智能问答接口
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;
    private final ConversationSessionMapper sessionMapper;
    private final ConversationMessageMapper messageMapper;
    private final UserService userService;
    private final StringRedisTemplate redisTemplate;

    private static final String HISTORY_CACHE_PREFIX = "rag:history:";
    private static final String SESSIONS_CACHE_PREFIX = "rag:sessions:user:";
    private static final int CACHE_TTL_MINUTES = 5;
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * RAG问答（同步）
     */
    @PostMapping("/ask")
    public Result ask(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Result.fail("问题不能为空");
        }
        ChatResponse response = ragService.ask(request);
        if (response.getSessionId() != null) {
            redisTemplate.delete(HISTORY_CACHE_PREFIX + response.getSessionId());
        }
        return Result.ok(response);
    }

    /**
     * RAG问答 — SSE 流式输出
     * <p>
     * POST /api/chat/ask/stream
     * <p>
     * 事件格式：
     * <pre>
     *   data: {"token":"文"}          → 逐字推送
     *   event: sources               → 引用来源
     *   data: {"sessionId":"..","sources":[...]}
     *   event: done                   → 完成
     *   event: error                  → 错误
     *   data: {"error":"..."}
     * </pre>
     */
    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data("{\"error\":\"问题不能为空\"}"));
                emitter.complete();
            } catch (Exception ignored) {}
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(120_000L); // 2 分钟超时
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 关键：在独立线程中执行 RAG 检索 + LLM 流式调用，
        // 让 controller 立即返回 emitter，Spring 框架才能开始推送 SSE 事件
        new Thread(() -> {
            try {
                SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
                securityContext.setAuthentication(authentication);
                SecurityContextHolder.setContext(securityContext);
                ragService.askStream(request, emitter);
            } finally {
                SecurityContextHolder.clearContext();
            }
        }).start();

        return emitter;
    }

    /**
     * 查询当前用户的会话列表
     */
    @GetMapping("/sessions")
    public Result sessions() {
        User currentUser = userService.getCurrentUser();
        if (currentUser == null) {
            return Result.fail("请先登录");
        }

        String cacheKey = SESSIONS_CACHE_PREFIX + currentUser.getId();

        // 1. 尝试 Redis
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(JSONUtil.parseArray(cached));
        }

        // 2. Redis miss → 查 MySQL
        LambdaQueryWrapper<ConversationSession> wrapper = new LambdaQueryWrapper<ConversationSession>()
                .orderByDesc(ConversationSession::getLastActiveTime);
        if (!"ADMIN".equals(currentUser.getRole())) {
            wrapper.eq(ConversationSession::getUserId, currentUser.getId());
        }

        List<ConversationSession> sessions = sessionMapper.selectList(wrapper);
        List<Map<String, Object>> result = sessions.stream().map(s -> {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", s.getSessionId());
            map.put("title", s.getTitle());
            map.put("createTime", s.getCreateTime() != null ? s.getCreateTime().format(DTF) : null);
            map.put("lastTime", s.getLastActiveTime() != null ? s.getLastActiveTime().format(DTF) : null);
            return map;
        }).toList();

        // 3. 回填 Redis
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        return Result.ok(result);
    }

    /**
     * 查询对话历史
     */
    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable String sessionId) {
        String cacheKey = HISTORY_CACHE_PREFIX + sessionId;

        // 1. Redis 命中 → 直接返回（跳过所有 DB 查询）
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (StrUtil.isNotBlank(cached)) {
            return Result.ok(JSONUtil.parseArray(cached));
        }

        // 2. Redis miss → 权限检查 + 查 MySQL
        User currentUser = userService.getCurrentUser();
        if (currentUser != null && !"ADMIN".equals(currentUser.getRole())) {
            ConversationSession session = sessionMapper.selectOne(
                    new LambdaQueryWrapper<ConversationSession>()
                            .eq(ConversationSession::getSessionId, sessionId));
            if (session != null && session.getUserId() != null
                    && !session.getUserId().equals(currentUser.getId())) {
                return Result.fail("无权查看该对话");
            }
        }
        List<ConversationMessage> messages = messageMapper.selectList(
                new LambdaQueryWrapper<ConversationMessage>()
                        .eq(ConversationMessage::getSessionId, sessionId)
                        .orderByAsc(ConversationMessage::getCreateTime)
        );

        List<Map<String, Object>> result = messages.stream().map(m -> {
            Map<String, Object> map = new HashMap<>();
            map.put("sessionId", m.getSessionId());
            map.put("question", m.getQuestion());
            map.put("answer", m.getAnswer());
            map.put("sources", m.getSources());
            map.put("createTime", m.getCreateTime() != null ? m.getCreateTime().format(DTF) : null);
            return map;
        }).toList();

        // 3. 回填 Redis
        redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(result), CACHE_TTL_MINUTES, TimeUnit.MINUTES);

        return Result.ok(result);
    }
}
