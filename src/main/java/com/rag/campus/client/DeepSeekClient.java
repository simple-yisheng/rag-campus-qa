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
     * 查询改写 — 将多轮对话中的追问改写为独立完整的问题
     * <p>
     * 使用专用的 system prompt + 低温度，确保改写结果稳定可控。
     *
     * @param userPrompt 包含对话历史+当前追问的 user prompt（由 RagPromptTemplate 构建）
     * @return 改写后的独立完整问题
     */
    public String rewriteQuery(String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content",
                "你是一个查询改写助手。你的任务是将用户的追问改写成独立完整的问题。"
                        + "补全省略的主语、指代和隐含引用。如果问题已完整，直接返回原句。"
                        + "只输出改写后的问题，不要加任何解释。"));
        messages.add(Map.of("role", "user", "content", userPrompt));
        return doChatLight(messages);
    }

    /**
     * 轻量 LLM 调用 — 用于查询改写等短输出场景
     * <p>
     * 使用更低的 max_tokens 和 temperature，减少延迟和成本。
     */
    private String doChatLight(List<Map<String, String>> messages) {
        try {
            String url = baseUrl + "/chat/completions";

            Map<String, Object> body = new HashMap<>();
            body.put("model", chatModel);
            body.put("messages", messages);
            body.put("temperature", 0.1);    // 极低温度，确保输出稳定
            body.put("max_tokens", 200);     // 查询改写只需一句话

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(JSONUtil.toJsonStr(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getBody() == null) {
                log.error("DeepSeek API 返回空响应(查询改写)");
                return null;
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("DeepSeek API 返回无choices(查询改写): {}", response.getBody());
                return null;
            }

            String content = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            return StrUtil.isBlank(content) ? null : content.trim();

        } catch (Exception e) {
            log.warn("查询改写失败，将使用原始问题继续", e);
            return null;
        }
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
            body.put("max_tokens", 4096);   // 足够输出长表格+解释

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
