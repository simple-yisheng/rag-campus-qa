package com.rag.campus.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 应用通用配置
 */
@Configuration
public class AppConfig {

    /**
     * RestTemplate — 用于调用 DeepSeek / DashScope 等外部HTTP API
     */
    @Bean
    public RestTemplate restTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        // LLM API 调用可能耗时较长，设置足够的超时时间
        factory.setConnectTimeout(30_000);   // 连接超时: 30s
        factory.setReadTimeout(120_000);     // 读取超时: 120s（LLM生成可能较慢）
        return new RestTemplate(factory);
    }
}
