package com.rag.campus.service.impl;

import com.rag.campus.config.RabbitMQConfig;
import com.rag.campus.service.DocumentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 文档处理消费者 — 异步处理文档分块+向量化
 * <p>
 * 面试要点：
 * 1. 为什么要异步？— 向量化API调用耗时（可能数秒到数十秒），同步返回用户体验差
 * 2. 为什么用MQ？— 削峰填谷 + 失败重试 + 解耦
 * 3. 消息可靠性？— 手动ACK + 失败记录到DB便于后续补偿
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentProcessConsumer {

    private final DocumentService documentService;

    /**
     * 监听文档处理队列
     * <p>
     * queues 常量在 RabbitMQConfig 中定义
     */
    @RabbitListener(queues = RabbitMQConfig.DOCUMENT_PROCESS_QUEUE)
    public void handleDocumentProcess(Long documentId) {
        log.info("收到文档处理消息: documentId={}", documentId);
        try {
            documentService.processDocument(documentId);
        } catch (Exception e) {
            log.error("文档处理失败: documentId={}", documentId, e);
            // 失败后不抛异常，避免MQ死循环重试
            // 后续可加死信队列 + 定时补偿任务（参考黑马点评的 SeckillMqFallbackRetryTask）
        }
    }
}
