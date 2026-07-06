package com.rag.campus.service;

import com.rag.campus.dto.ChatRequest;
import com.rag.campus.dto.ChatResponse;

/**
 * RAG 问答服务接口
 */
public interface RagService {

    /**
     * RAG问答 — 检索增强生成
     * <p>
     * 流程：
     * 1. 将用户问题向量化
     * 2. 在向量索引中检索Top-K相似chunk
     * 3. 组装Prompt（系统提示词 + 检索上下文 + 用户问题）
     * 4. 调用LLM生成回答
     * 5. 保存对话记录，返回结果
     *
     * @param request 对话请求（sessionId + question）
     * @return 对话响应（answer + sources）
     */
    ChatResponse ask(ChatRequest request);
}
