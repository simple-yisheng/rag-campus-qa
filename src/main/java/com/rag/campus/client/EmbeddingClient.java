package com.rag.campus.client;

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
 * Embedding 向量化客户端
 * <p>
 * 默认对接阿里云 DashScope text-embedding-v3，
 * 可切换为其他兼容 OpenAI Embedding API 的服务
 */
@Slf4j
@Component
public class EmbeddingClient {

    @Value("${embedding.provider}")
    private String provider;

    @Value("${embedding.dashscope.api-key}")
    private String dashscopeApiKey;

    @Value("${embedding.dashscope.base-url}")
    private String dashscopeBaseUrl;

    @Value("${embedding.dashscope.model}")
    private String dashscopeModel;

    private final RestTemplate restTemplate;

    public EmbeddingClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 将单段文本向量化
     *
     * @param text 输入文本
     * @return 向量（float数组）
     */
    public float[] embed(String text) {
        if ("dashscope".equals(provider)) {
            return embedViaDashScope(text);
        }
        throw new IllegalStateException("不支持的Embedding服务商: " + provider);
    }

    /**
     * 批量向量化（自动分批，DashScope 单次最多10条）
     *
     * @param texts 文本列表
     * @return 向量列表，与输入顺序一致
     */
    public List<float[]> embedBatch(List<String> texts) {
        if ("dashscope".equals(provider)) {
            // DashScope 限制单次最多 10 条，超限自动分批
            if (texts.size() <= 10) {
                return embedBatchViaDashScope(texts);
            }
            List<float[]> all = new ArrayList<>();
            for (int i = 0; i < texts.size(); i += 10) {
                int end = Math.min(i + 10, texts.size());
                List<String> subBatch = texts.subList(i, end);
                List<float[]> subResult = embedBatchViaDashScope(subBatch);
                if (subResult.isEmpty()) {
                    log.error("Embedding 分批失败: 第{}-{}条", i + 1, end);
                    return Collections.emptyList();
                }
                all.addAll(subResult);
                // 批次间加 200ms 延迟避免触发 QPS 限流
                if (end < texts.size()) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
            }
            return all;
        }
        throw new IllegalStateException("不支持的Embedding服务商: " + provider);
    }

    // ==================== DashScope 实现 ====================

    private float[] embedViaDashScope(String text) {
        List<float[]> results = embedBatchViaDashScope(Collections.singletonList(text));
        return results.isEmpty() ? new float[0] : results.get(0);
    }

    private List<float[]> embedBatchViaDashScope(List<String> texts) {
        String url = dashscopeBaseUrl + "/compatible-mode/v1/embeddings";

        Map<String, Object> body = new HashMap<>();
        body.put("model", dashscopeModel);
        body.put("input", texts);

        // DashScope 的 text-embedding-v3 支持指定维度
        Map<String, Object> params = new HashMap<>();
        params.put("text_type", "document");
        body.put("parameters", params);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + dashscopeApiKey);

        try {
            log.info("调用 Embedding API, key前缀={}, 文本数={}",
                    dashscopeApiKey.substring(0, Math.min(8, dashscopeApiKey.length())), texts.size());
            HttpEntity<String> entity = new HttpEntity<>(JSONUtil.toJsonStr(body), headers);
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            if (response.getBody() == null) {
                log.error("Embedding API 返回空响应");
                return Collections.emptyList();
            }

            JSONObject json = JSONUtil.parseObj(response.getBody());
            JSONArray dataArray = json.getJSONArray("data");

            List<float[]> embeddings = new ArrayList<>();
            for (int i = 0; i < dataArray.size(); i++) {
                JSONArray embeddingJson = dataArray.getJSONObject(i).getJSONArray("embedding");
                float[] vector = new float[embeddingJson.size()];
                for (int j = 0; j < embeddingJson.size(); j++) {
                    vector[j] = embeddingJson.getFloat(j).floatValue();
                }
                embeddings.add(vector);
            }

            log.info("Embedding 完成: {} 条文本 → {} 条向量", texts.size(), embeddings.size());
            return embeddings;

        } catch (org.springframework.web.client.HttpClientErrorException e) {
            log.error("Embedding API 调用失败: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("Embedding API 调用失败", e);
            return Collections.emptyList();
        }
    }
}
