package com.rag.campus.client;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek LLM 客户端
 * <p>
 * 封装 DeepSeek Chat Completion API 调用，支持多轮对话
 */
@Slf4j
@Component
public class DeepSeekClient {

    @Value("${deepseek.api-key}")
    private String apiKey;

    @Value("${deepseek.base-url}")
    private String baseUrl;

    @Value("${deepseek.chat-model}")
    private String chatModel;

    private final RestTemplate restTemplate;

    public DeepSeekClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 单轮对话 — 发送prompt，返回模型回答
     *
     * @param systemPrompt 系统提示词（定义角色和行为）
     * @param userMessage  用户消息
     * @return 模型回答文本
     */
    public String chat(String systemPrompt, String userMessage) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        messages.add(Map.of("role", "user", "content", userMessage));
        return doChat(messages);
    }

    /**
     * 多轮对话 — 携带历史记录
     *
     * @param messages 完整对话历史，格式: [{"role":"system","content":"..."}, {"role":"user","content":"..."}, ...]
     * @return 模型回答文本
     */
    public String chatWithHistory(List<Map<String, String>> messages) {
        return doChat(messages);
    }

    /**
     * 实际调用 DeepSeek Chat API
     */
    private String doChat(List<Map<String, String>> messages) {
        try {
            String url = baseUrl + "/chat/completions";

            Map<String, Object> body = new HashMap<>();
            body.put("model", chatModel);
            body.put("messages", messages);
            body.put("temperature", 0.3);   // 低温度，保证回答准确性
            body.put("max_tokens", 2000);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(JSONUtil.toJsonStr(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getBody() == null) {
                log.error("DeepSeek API 返回空响应");
                return "抱歉，服务暂时不可用，请稍后重试。";
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("DeepSeek API 返回无choices: {}", response.getBody());
                return "抱歉，服务暂时不可用，请稍后重试。";
            }

            String content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            if (StrUtil.isBlank(content)) {
                return "抱歉，我暂时无法回答这个问题。";
            }

            return content.trim();

        } catch (Exception e) {
            log.error("调用 DeepSeek API 失败", e);
            return "抱歉，服务调用失败：" + e.getMessage();
        }
    }
}
