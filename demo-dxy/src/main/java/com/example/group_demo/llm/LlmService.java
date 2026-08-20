package com.example.group_demo.llm;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.group_demo.config.RestClientFactory;
import com.example.group_demo.tool.BotTool;
import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);
    private static final String SYSTEM_PROMPT =
        "你是微信机器人助手，请用简洁的中文回答问题。工具返回的列表内容必须完整逐条展示给用户，不要只做概括。"
            + "当工具返回联网搜索结果时，必须以搜索结果为准，优先引用来源，不得因为与你的训练知识不一致而否定搜索结果。";

    private final LlmProperties properties;
    private final RestClient restClient;
    private final ConversationMemoryService conversationMemory;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(LlmProperties properties, ConversationMemoryService conversationMemory) {
        this(properties, conversationMemory, new ToolRegistry(List.of()));
    }

    @Autowired
    public LlmService(LlmProperties properties, ConversationMemoryService conversationMemory,
                      ToolRegistry toolRegistry) {
        this.properties = properties;
        this.conversationMemory = conversationMemory;
        this.toolRegistry = toolRegistry;
        this.restClient = RestClientFactory.builder().baseUrl(properties.getBaseUrl()).build();
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

    public String chat(String userId, String userText) {
        List<Map<String, Object>> messages = buildMemoryMessages(userId, userText);
        String reply = complete(properties.getModel(), messages);
        conversationMemory.append(userId, "user", userText);
        conversationMemory.append(userId, "assistant", reply);
        return reply;
    }

    public String chatWithTools(String userId, String userText) {
        List<Map<String, Object>> messages = buildMemoryMessages(userId, userText);

        List<Map<String, Object>> toolSchemas = toolRegistry.jsonSchemas();
        int maxRounds = Math.max(1, properties.getToolMaxRounds());
        String lastToolResult = null;
        for (int round = 0; round < maxRounds; round++) {
            ChatResponse response = completeResponse(properties.getModel(), messages, toolSchemas);
            ChatResponse.Message message = firstMessage(response);
            List<ToolCall> toolCalls = message.toolCalls();
            if (toolCalls == null || toolCalls.isEmpty()) {
                String content = message.content();
                String reply;
                if (content == null || content.isBlank()) {
                    if (lastToolResult == null) {
                        throw new IllegalStateException("LLM 返回内容为空");
                    }
                    log.warn("LLM 最终回复为空，回退为最后一次工具结果");
                    reply = lastToolResult;
                } else {
                    reply = content.trim();
                }
                conversationMemory.append(userId, "user", userText);
                conversationMemory.append(userId, "assistant", reply);
                log.info("LLM 工具对话完成 model={} rounds={}", properties.getModel(), round + 1);
                return reply;
            }

            messages.add(toAssistantToolMessage(message, toolCalls));
            String relayedResult = null;
            String relayedToolName = null;
            for (ToolCall toolCall : toolCalls) {
                String callId = toolCall.id() == null ? "call_" + System.nanoTime() : toolCall.id();
                String toolName = toolCall.function() == null ? null : toolCall.function().name();
                String arguments = toolCall.function() == null ? "{}" : toolCall.function().arguments();
                if ("web_search".equals(toolName)) {
                    arguments = withOriginalSearchQuery(arguments, userText);
                }
                String result = toolRegistry.execute(userId, toolName, arguments);
                lastToolResult = result;
                BotTool tool = toolRegistry.find(toolName);
                if (tool != null && tool.relayToUser()) {
                    relayedResult = result;
                    relayedToolName = toolName;
                }
                Map<String, Object> toolMessage = new LinkedHashMap<>();
                toolMessage.put("role", "tool");
                toolMessage.put("tool_call_id", callId);
                toolMessage.put("content", result);
                messages.add(toolMessage);
            }
            if (relayedResult != null) {
                log.info("工具结果直接回复 userId={} tool={}", userId, relayedToolName);
                conversationMemory.append(userId, "user", userText);
                conversationMemory.append(userId, "assistant", relayedResult);
                return relayedResult;
            }
        }
        if (lastToolResult != null) {
            log.warn("LLM 工具调用达到最大轮数 {}，回退为最后一次工具结果", maxRounds);
            conversationMemory.append(userId, "user", userText);
            conversationMemory.append(userId, "assistant", lastToolResult);
            return lastToolResult;
        }
        throw new IllegalStateException("LLM 工具调用超过最大轮数 " + maxRounds);
    }

    private String withOriginalSearchQuery(String argumentsJson, String userText) {
        try {
            JsonNode node = objectMapper.readTree(argumentsJson);
            if (node != null && node.isObject()) {
                ObjectNode arguments = (ObjectNode) node;
                arguments.put("query", userText);
                return objectMapper.writeValueAsString(arguments);
            }
        } catch (Exception e) {
            log.warn("web_search 参数改写失败，沿用原参数：{}", e.getMessage());
        }
        return argumentsJson;
    }

    private List<Map<String, Object>> buildMemoryMessages(String userId, String userText) {
        ConversationMemoryService.ChatContext context = prepareMemoryContext(userId);
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        if (context.summary() != null && !context.summary().isBlank()) {
            messages.add(Map.of(
                "role", "system",
                "content", "以下是更早对话的摘要：\n" + context.summary()
            ));
        }
        for (ConversationMemoryService.ChatTurn turn : context.turns()) {
            messages.add(Map.of("role", turn.role(), "content", turn.content()));
        }
        messages.add(Map.of("role", "user", "content", userText));
        return messages;
    }

    private ConversationMemoryService.ChatContext prepareMemoryContext(String userId) {
        LlmProperties.Memory memoryConfig = properties.getMemory();
        ConversationMemoryService.ChatContext context = conversationMemory.load(userId);
        String summary = context.summary();
        List<ConversationMemoryService.ChatTurn> turns = context.turns();

        int thresholdMessages = memoryConfig.getSummaryThresholdTurns() * 2;
        int keepMessages = memoryConfig.getRecentTurns() * 2;
        if (memoryConfig.isSummaryEnabled()
            && turns.size() > thresholdMessages
            && turns.size() > keepMessages) {
            List<ConversationMemoryService.ChatTurn> older =
                turns.subList(0, turns.size() - keepMessages);
            List<ConversationMemoryService.ChatTurn> recent =
                new ArrayList<>(turns.subList(turns.size() - keepMessages, turns.size()));
            try {
                summary = summarize(summary, older);
                conversationMemory.compact(userId, summary, older.size());
                turns = recent;
            } catch (Exception e) {
                log.warn("对话摘要生成失败，本次保留完整对话上下文", e);
            }
        }
        return new ConversationMemoryService.ChatContext(summary, turns);
    }

    private String summarize(String existingSummary,
                             List<ConversationMemoryService.ChatTurn> turns) {
        StringBuilder dialog = new StringBuilder();
        if (existingSummary != null && !existingSummary.isBlank()) {
            dialog.append("已有摘要：\n").append(existingSummary).append("\n\n");
        }
        dialog.append("新增对话：\n");
        for (ConversationMemoryService.ChatTurn turn : turns) {
            String speaker = "user".equals(turn.role()) ? "用户" : "助手";
            dialog.append(speaker).append("：").append(turn.content()).append("\n");
        }
        String prompt = "请用简洁中文总结这段微信机器人对话的长期要点，包括用户的名字、偏好、任务和重要约定。"
            + "只输出摘要，不要其他内容。\n\n" + dialog;
        return complete(
            properties.getModel(),
            List.of(
                Map.of("role", "system", "content", "你是对话摘要助手。"),
                Map.of("role", "user", "content", prompt)
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
        ChatResponse response = completeResponse(model, messages, List.of());
        String content = firstMessage(response).content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("LLM 返回内容为空");
        }
        return content.trim();
    }

    private ChatResponse completeResponse(String model, List<Map<String, Object>> messages,
                                          List<Map<String, Object>> tools) {
        if (!isConfigured()) {
            throw new IllegalStateException("LLM API key 未配置，请设置 DASHSCOPE_API_KEY");
        }

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        if (tools != null && !tools.isEmpty()) {
            requestBody.put("tools", tools);
        }

        ChatResponse response = restClient.post()
            .uri("/chat/completions")
            .header("Authorization", "Bearer " + properties.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(ChatResponse.class);

        ChatResponse.Message message = firstMessage(response);
        log.info("LLM 调用成功，模型={}", model);
        return response;
    }

    private ChatResponse.Message firstMessage(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("LLM 返回结果为空");
        }
        ChatResponse.Message message = response.choices().get(0).message();
        if (message == null) {
            throw new IllegalStateException("LLM 返回消息为空");
        }
        return message;
    }

    private Map<String, Object> toAssistantToolMessage(ChatResponse.Message message,
                                                       List<ToolCall> toolCalls) {
        Map<String, Object> assistant = new LinkedHashMap<>();
        assistant.put("role", "assistant");
        if (message.content() != null) {
            assistant.put("content", message.content());
        }
        assistant.put("tool_calls", toolCalls.stream().map(ToolCall::toMap).toList());
        return assistant;
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
        public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
        }

        public record Message(String role, String content,
                              @JsonProperty("tool_calls") List<ToolCall> toolCalls) {
        }
    }

    public record ToolCall(String id, String type, FunctionCall function) {
        public record FunctionCall(String name, String arguments) {
        }

        public Map<String, Object> toMap() {
            return Map.of(
                "id", id,
                "type", type == null ? "function" : type,
                "function", Map.of("name", function.name(), "arguments", function.arguments())
            );
        }
    }
}
