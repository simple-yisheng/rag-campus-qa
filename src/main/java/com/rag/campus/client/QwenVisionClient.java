package com.rag.campus.client;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 通义千问 VL 多模态客户端
 * <p>
 * 通过 DashScope OpenAI 兼容接口调用 Qwen VL 模型，
 * 实现图片内容描述和扫描件 OCR 识别。
 * <p>
 * 接口格式兼容 OpenAI Chat Completions，
 * 图片以 base64 data URL 传入 content 数组。
 *
 * <pre>
 *   POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 *   {
 *     "model": "qwen-vl-plus",
 *     "messages": [{
 *       "role": "user",
 *       "content": [
 *         {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}},
 *         {"type": "text", "text": "请描述这张图片"}
 *       ]
 *     }]
 *   }
 * </pre>
 */
@Slf4j
@Component
public class QwenVisionClient {

    @Value("${embedding.dashscope.api-key}")
    private String apiKey;

    @Value("${rag.vision.model:qwen-vl-plus}")
    private String model;

    /** DashScope OpenAI 兼容端点 */
    private static final String BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private final RestTemplate restTemplate;

    public QwenVisionClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // ==================== 公开方法 ====================

    /**
     * 描述图片内容（流程图、示意图、表格等）
     *
     * @param imageBytes 图片字节（PNG/JPEG）
     * @param mimeType   图片 MIME 类型
     * @param context    可选的上下文提示（如"这是某章节的配图"）
     * @return 图片描述文本，失败返回空字符串
     */
    public String describeImage(byte[] imageBytes, String mimeType, String context) {
        String prompt = "请用中文详细描述这张图片的内容。";
        if (StrUtil.isNotBlank(context)) {
            prompt = "这张图片出现在文档中的" + context + "。请用中文详细描述图片内容，说明关键信息和数据。";
        }
        return callVisionAPI(imageBytes, mimeType, prompt, 500);
    }

    /**
     * OCR 识别扫描件页面中的文字（扫描版 PDF 用）
     *
     * @param imageBytes 扫描页面图片字节
     * @return 识别出的文字内容，失败返回空字符串
     */
    public String ocrPage(byte[] imageBytes) {
        String prompt = "请识别并提取这张图片中的所有文字内容。保持原文的段落结构，不要添加额外解释。"
                + "如果图片中有表格，请用 Markdown 表格格式输出。"
                + "只输出识别到的文字，不要加前缀说明。";
        return callVisionAPI(imageBytes, "image/png", prompt, 2000);
    }

    // ==================== 内部实现 ====================

    /**
     * 通用 Vision API 调用
     *
     * @param imageBytes 图片字节
     * @param mimeType   图片 MIME 类型
     * @param prompt     文字提示
     * @param maxTokens  最大输出 token 数
     * @return 模型返回文本，失败返回空字符串
     */
    private String callVisionAPI(byte[] imageBytes, String mimeType, String prompt, int maxTokens) {
        try {
            String base64 = Base64.encode(imageBytes);
            String dataUrl = "data:" + mimeType + ";base64," + base64;

            // 构建 content 数组: [{"type":"image_url","image_url":{"url":"..."}}, {"type":"text","text":"..."}]
            List<Map<String, Object>> content = new ArrayList<>();

            Map<String, Object> imagePart = new HashMap<>();
            imagePart.put("type", "image_url");
            imagePart.put("image_url", Map.of("url", dataUrl));
            content.add(imagePart);

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("type", "text");
            textPart.put("text", prompt);
            content.add(textPart);

            // 消息体
            Map<String, Object> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", content);

            Map<String, Object> body = new HashMap<>();
            body.put("model", model);
            body.put("messages", List.of(message));
            body.put("max_tokens", maxTokens);
            body.put("temperature", 0.1);  // 低温度，确保输出稳定

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            HttpEntity<String> entity = new HttpEntity<>(JSONUtil.toJsonStr(body), headers);
            String url = BASE_URL + "/chat/completions";

            log.debug("调用 Qwen VL API: model={}, imageSize={}KB, promptLen={}",
                    model, imageBytes.length / 1024, prompt.length());

            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, entity, String.class);

            if (response.getBody() == null) {
                log.error("Qwen VL API 返回空响应");
                return "";
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            JSONArray choices = json.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) {
                log.error("Qwen VL API 返回无 choices: {}", response.getBody());
                return "";
            }

            String content1 = choices.getJSONObject(0)
                    .getJSONObject("message")
                    .getStr("content");

            return StrUtil.isBlank(content1) ? "" : content1.trim();

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.warn("Qwen VL API 调用失败: status={}, body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            return "";
        } catch (Exception e) {
            log.warn("Qwen VL API 调用异常: {}", e.getMessage());
            return "";
        }
    }
}
