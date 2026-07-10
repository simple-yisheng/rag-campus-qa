package com.rag.campus.service;

import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * RAG 问答服务接口
 */
public interface RagService {

    /**
     * RAG问答 — 检索增强生成（同步，等待完整回答）
     *
     * @param request 对话请求（sessionId + question）
     * @return 对话响应（answer + sources）
     */
    ChatResponse ask(ChatRequest request);

    /**
     * RAG问答 — SSE 流式输出
     * <p>
     * 检索 + Prompt 组装同上，LLM 生成阶段通过 SSE 逐 token 推送，
     * 前端实现打字机效果。
     *
     * @param request 对话请求
     * @param emitter SSE 发射器（Controller 创建并返回给前端）
     */
    void askStream(ChatRequest request, SseEmitter emitter);
}
