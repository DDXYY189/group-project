package com.example.wechatbot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final RestTemplate restTemplate;

    @Value("${llm.api-key}")
    private String apiKey;

    @Value("${llm.text-model:qwen-turbo}")
    private String textModel;

    @Value("${llm.vision-model:qwen-vl-plus}")
    private String visionModel;

    @Value("${llm.asr-model:qwen-audio-turbo}")
    private String asrModel;

    @Value("${llm.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseUrl;

    private static final String SYSTEM_PROMPT =
            "你是一个智能微信助手。请用简洁友好的中文回复用户, 回复不超过200字。";

    public LlmService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public boolean isReady() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    public String chat(String userMessage) {
        if (!isReady()) {
            log.warn("DashScope API Key 未配置, 无法调用大模型");
            return "[API Key 未配置, 请在 application.yml 中设置 llm.api-key]";
        }

        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", textModel);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userMessage)
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "[大模型未返回有效内容]";
        } catch (Exception e) {
            log.error("文本对话调用失败: {}", e.getMessage(), e);
            return "[调用大模型失败: " + e.getMessage() + "]";
        }
    }

    public String chatWithImage(byte[] imageBytes, String prompt) {
        if (!isReady()) {
            return "[API Key 未配置]";
        }

        String base64Image = Base64.getEncoder().encodeToString(imageBytes);
        String dataUrl = "data:image/png;base64," + base64Image;

        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", visionModel);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", "你是一个图片分析助手, 请用中文简洁描述。"),
                Map.of("role", "user", "content", new Object[]{
                        Map.of("type", "image_url", "image_url", Map.of("url", dataUrl)),
                        Map.of("type", "text", "text", prompt != null ? prompt : "请描述这张图片的内容")
                })
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "[图片理解未返回有效内容]";
        } catch (Exception e) {
            log.error("图片理解调用失败: {}", e.getMessage(), e);
            return "[图片理解失败: " + e.getMessage() + "]";
        }
    }

    public String chatWithAudio(byte[] audioBytes) {
        if (!isReady()) {
            return "[API Key 未配置]";
        }

        String base64Audio = Base64.getEncoder().encodeToString(audioBytes);
        String dataUrl = "data:audio/wav;base64," + base64Audio;

        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", asrModel);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", "你是语音识别助手, 请将语音转为文字并简要回复。"),
                Map.of("role", "user", "content", new Object[]{
                        Map.of("type", "input_audio", "input_audio", Map.of("data", dataUrl)),
                        Map.of("type", "text", "text", "请将这段语音转为文字")
                })
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "[语音识别未返回有效内容]";
        } catch (Exception e) {
            log.error("语音识别调用失败: {}", e.getMessage(), e);
            return "[语音识别失败: " + e.getMessage() + "]";
        }
    }

    private JSONObject postRequest(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return JSON.parseObject(resp.getBody());
    }
}
