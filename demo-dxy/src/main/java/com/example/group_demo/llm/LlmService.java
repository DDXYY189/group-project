package com.example.group_demo.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final String SYSTEM_PROMPT = "你是微信机器人助手，请用简洁的中文回答问题。";

    private final LlmProperties properties;
    private final RestClient restClient;

    public LlmService(LlmProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public boolean isConfigured() {
        return properties.getApiKey() != null && !properties.getApiKey().isBlank();
    }

    public String chat(String userText) {
        return complete(
            properties.getModel(),
            List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userText)
            )
        );
    }

    public String chatWithImage(String userText, byte[] imageBytes, String fileName) {
        String prompt = (userText == null || userText.isBlank()) ? "请描述这张图片" : userText;
        String dataUri = "data:" + mimeType(fileName) + ";base64,"
            + Base64.getEncoder().encodeToString(imageBytes);

        Map<String, Object> textPart = Map.of("type", "text", "text", prompt);
        Map<String, Object> imagePart = Map.of(
            "type", "image_url",
            "image_url", Map.of("url", dataUri)
        );
        Map<String, Object> userMessage = Map.of(
            "role", "user",
            "content", List.of(textPart, imagePart)
        );

        return complete(
            properties.getVisionModel(),
            List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                userMessage
            )
        );
    }

    public String chatRaw(String systemPrompt, String userText) {
        return complete(
            properties.getModel(),
            List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userText)
            )
        );
    }

    private String complete(String model, List<Map<String, Object>> messages) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM API key 未配置，请设置 DASHSCOPE_API_KEY");
        }

        Map<String, Object> requestBody = Map.of(
            "model", model,
            "messages", messages
        );

        ChatResponse response = restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(ChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("LLM 返回结果为空");
        }

        String content = response.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM 返回内容为空");
        }
        log.info("LLM 调用成功，模型={}", model);
        return content.trim();
    }

    private String mimeType(String fileName) {
        if (fileName == null) {
            return "image/png";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (lower.endsWith(".gif")) {
            return "image/gif";
        }
        return "image/png";
    }

    public record ChatResponse(List<Choice> choices) {
        public record Choice(Message message) {
        }

        public record Message(String content) {
        }
    }
}
