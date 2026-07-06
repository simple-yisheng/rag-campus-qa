package com.rag.campus.config;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 */
@Configuration
public class RabbitMQConfig {

    // ==================== 文档处理相关 ====================

    public static final String DOCUMENT_EXCHANGE = "rag.document.exchange";
    public static final String DOCUMENT_PROCESS_QUEUE = "rag.document.process.queue";
    public static final String DOCUMENT_PROCESS_ROUTING_KEY = "rag.document.process";

    /** 文档处理 - Topic交换机 */
    @Bean
    public TopicExchange documentExchange() {
        return new TopicExchange(DOCUMENT_EXCHANGE, true, false);
    }

    /** 文档处理 - 队列（持久化） */
    @Bean
    public Queue documentProcessQueue() {
        return QueueBuilder.durable(DOCUMENT_PROCESS_QUEUE).build();
    }

    /** 绑定队列到交换机 */
    @Bean
    public Binding documentProcessBinding() {
        return BindingBuilder.bind(documentProcessQueue())
                .to(documentExchange())
                .with(DOCUMENT_PROCESS_ROUTING_KEY);
    }
}
