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
import org.springframework.web.bind.annotation.*;

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
     * RAG问答
     */
    @PostMapping("/ask")
    public Result ask(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Result.fail("问题不能为空");
        }
        ChatResponse response = ragService.ask(request);
        // 新消息产生，清掉该 session 的历史缓存
        if (response.getSessionId() != null) {
            redisTemplate.delete(HISTORY_CACHE_PREFIX + response.getSessionId());
        }
        return Result.ok(response);
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
