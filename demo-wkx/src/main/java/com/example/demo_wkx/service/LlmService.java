package com.example.demo_wkx.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LlmService {

    @Value("${llm.api-key:}")
    private String apiKey;

    @Value("${llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${llm.model:deepseek-chat}")
    private String model;

    @Value("${llm.system-prompt:你是一个友好的微信AI助手，请用简洁的中文回答用户的问题。}")
    private String systemPrompt;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, List<Object[]>> conversationHistory = new ConcurrentHashMap<>();

    /**
     * 使用 LLM 生成文本回复 (OpenAI兼容接口，支持DeepSeek等)
     */
    public String chat(String userId, String userMessage) {
        try {
            List<Object[]> history = conversationHistory.computeIfAbsent(userId, k -> new ArrayList<>());

            ArrayNode messages = objectMapper.createArrayNode();

            ObjectNode systemMsg = objectMapper.createObjectNode();
            systemMsg.put("role", "system");
            systemMsg.put("content", systemPrompt);
            messages.add(systemMsg);

            int startIdx = Math.max(0, history.size() - 10);
            for (int i = startIdx; i < history.size(); i++) {
                Object[] pair = history.get(i);
                ObjectNode userMsg = objectMapper.createObjectNode();
                userMsg.put("role", "user");
                userMsg.put("content", (String) pair[0]);
                messages.add(userMsg);

                ObjectNode assistantMsg = objectMapper.createObjectNode();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", (String) pair[1]);
                messages.add(assistantMsg);
            }

            ObjectNode currentMsg = objectMapper.createObjectNode();
            currentMsg.put("role", "user");
            currentMsg.put("content", userMessage);
            messages.add(currentMsg);

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("model", model);
            requestBody.set("messages", messages);
            requestBody.put("stream", false);
            requestBody.put("max_tokens", 2048);
            requestBody.put("temperature", 0.7);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                    .timeout(Duration.ofSeconds(60))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode root = objectMapper.readTree(response.body());
                String reply = root.path("choices").path(0).path("message").path("content").asText();

                history.add(new Object[]{userMessage, reply});
                while (history.size() > 20) {
                    history.remove(0);
                }

                return reply;
            } else {
                return "LLM请求失败，状态码: " + response.statusCode() + "，请检查API Key配置。";
            }
        } catch (Exception e) {
            return "LLM处理异常: " + e.getMessage();
        }
    }

    /**
     * 使用 Pollinations.ai 免费生成图片 (无需API Key)
     */
    public byte[] generateImage(String prompt) {
        try {
            String encodedPrompt = URLEncoder.encode(prompt, StandardCharsets.UTF_8);
            String imageUrl = "https://image.pollinations.ai/prompt/" + encodedPrompt
                    + "?width=1024&height=1024&nologo=true";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .GET()
                    .timeout(Duration.ofSeconds(90))
                    .build();

            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                return response.body();
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 文本转语音 (TODO: 集成TTS API)
     */
    public byte[] textToSpeech(String text) {
        // TODO: 集成TTS API (如Azure TTS, 百度TTS, 或Edge TTS)
        // 微信语音格式为silk，需要额外的音频格式转换
        return null;
    }

    /**
     * 清除用户对话历史
     */
    public void clearHistory(String userId) {
        conversationHistory.remove(userId);
    }
}
