package com.rag.campus.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.rag.campus.common.Result;
import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import com.rag.campus.entity.Conversation;
import com.rag.campus.mapper.ConversationMapper;
import com.rag.campus.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 智能问答接口
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final RagService ragService;
    private final ConversationMapper conversationMapper;

    /**
     * RAG问答
     * <p>
     * POST /api/chat/ask
     * Body: { "sessionId": "abc123", "question": "国家奖学金评选条件是什么？" }
     * <p>
     * - 首次提问不传 sessionId，系统自动生成
     * - 后续提问传入 sessionId 实现多轮对话
     */
    @PostMapping("/ask")
    public Result ask(@RequestBody ChatRequest request) {
        if (request.getQuestion() == null || request.getQuestion().isBlank()) {
            return Result.fail("问题不能为空");
        }
        ChatResponse response = ragService.ask(request);
        return Result.ok(response);
    }

    /**
     * 查询对话历史
     * <p>
     * GET /api/chat/history/{sessionId}
     */
    @GetMapping("/history/{sessionId}")
    public Result history(@PathVariable String sessionId) {
        List<Conversation> conversations = conversationMapper.selectList(
                new LambdaQueryWrapper<Conversation>()
                        .eq(Conversation::getSessionId, sessionId)
                        .orderByAsc(Conversation::getCreateTime)
        );
        return Result.ok(conversations);
    }
}
