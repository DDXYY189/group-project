package com.example.demo.llm;

import com.example.demo.config.DeepSeekProperties;
import com.example.demo.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private static final String CHAT_SYSTEM_PROMPT =
            "你是一个通过微信提供服务的智能助手，请用简洁、友好、准确的中文回答用户的问题。";

    private static final String DECIDE_SYSTEM_PROMPT =
            "你是微信机器人。请判断回复方式，并只输出一个 JSON 对象，不要输出任何其他文字。\n"
          + "JSON 格式：{\"intent\":\"text|voice|image\",\"content\":\"内容\"}\n"
          + "规则：\n"
          + "1. 用户明确要求语音回复时，intent 设为 voice，content 写要朗读的内容。\n"
          + "2. 用户要求生成或画图片时，intent 设为 image，content 写图片描述。\n"
          + "3. 其他情况 intent 设为 text，content 留空字符串（正文交给后续工具流程）。";

    private static final String TOOL_SYSTEM_PROMPT =
            "你是一个微信智能助手。当用户需要查询天气或做数学计算时，调用对应工具；"
          + "拿到工具结果后，用简洁、友好、准确的中文回答用户。";

    private static final int MAX_TOOL_ROUNDS = 6;

    private final DeepSeekProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LlmService(DeepSeekProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .build();
    }

    public String chat(String userText) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "机器人还没有配置大模型 API Key，请先设置环境变量 DEEPSEEK_API_KEY。";
        }

        try {
            JsonNode content = call(apiKey, List.of(
                    Map.of("role", "system", "content", CHAT_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userText)
            ), false);

            if (content.isMissingNode()) {
                return "抱歉，大模型没有返回有效内容。";
            }
            return content.asText();
        } catch (Exception e) {
            log.error("调用 DeepSeek 失败", e);
            return "抱歉，调用大模型失败：" + e.getMessage();
        }
    }

    public Decision decide(String userText) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return Decision.text(userText);
        }

        try {
            JsonNode content = call(apiKey, List.of(
                    Map.of("role", "system", "content", DECIDE_SYSTEM_PROMPT),
                    Map.of("role", "user", "content", userText)
            ), true);

            if (content.isMissingNode()) {
                return Decision.text(userText);
            }

            String json = content.asText();
            try {
                return objectMapper.readValue(json, Decision.class);
            } catch (Exception e) {
                log.warn("意图 JSON 解析失败，按纯文字处理：{}", json);
                return Decision.text(json);
            }
        } catch (Exception e) {
            log.error("意图识别失败", e);
            return Decision.text(userText);
        }
    }

    /**
     * 带工具调用的对话。模型可能连续多轮调用工具，这里循环执行直到模型给出最终回答。
     */
    public String runWithTools(String userText, List<Tool> tools) {
        String apiKey = resolveApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "机器人还没有配置大模型 API Key，请先设置环境变量 DEEPSEEK_API_KEY。";
        }

        try {
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", TOOL_SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", userText));

            for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
                JsonNode message = callForMessage(apiKey, messages, tools);
                JsonNode toolCalls = message.path("tool_calls");

                if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                    String content = message.path("content").asText("");
                    return StringUtils.hasText(content) ? content : "（模型没有返回内容）";
                }

                messages.add(assistantMessageWithToolCalls(message, toolCalls));
                for (JsonNode toolCall : toolCalls) {
                    String id = toolCall.path("id").asText("");
                    String name = toolCall.path("function").path("name").asText("");
                    String argumentsText = toolCall.path("function").path("arguments").asText("");
                    JsonNode arguments =
                            objectMapper.readTree(
                                    StringUtils.hasText(argumentsText) ? argumentsText : "{}");
                    String result = executeTool(name, arguments, tools);
                    log.info("工具 {} 被调用，参数 {}，结果 {}",
                            name, arguments, abbreviate(result, 120));

                    messages.add(Map.of(
                            "role", "tool",
                            "tool_call_id", id,
                            "content", result));
                }
            }
            return "工具调用轮次过多，已停止。";
        } catch (Exception e) {
            log.error("工具调用失败", e);
            return "抱歉，工具调用失败：" + e.getMessage();
        }
    }

    private Map<String, Object> assistantMessageWithToolCalls(
            JsonNode message, JsonNode toolCalls) {
        Map<String, Object> assistant = new HashMap<>();
        assistant.put("role", "assistant");
        assistant.put("content", message.path("content").asText(null));
        assistant.put("tool_calls", toolCalls);
        return assistant;
    }

    private JsonNode callForMessage(
            String apiKey, List<Map<String, Object>> messages, List<Tool> tools) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", false);
        body.put("tools", tools.stream().map(this::toolSchema).toList());

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return response.path("choices").path(0).path("message");
    }

    private Map<String, Object> toolSchema(Tool tool) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "parameters", tool.parametersSchema()));
    }

    private String executeTool(String name, JsonNode arguments, List<Tool> tools) {
        for (Tool tool : tools) {
            if (tool.name().equals(name)) {
                return tool.execute(arguments);
            }
        }
        return "未找到工具：" + name;
    }

    private JsonNode call(
            String apiKey, List<Map<String, String>> messages, boolean jsonMode) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", properties.getModel());
        body.put("messages", messages);
        body.put("stream", false);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return response.path("choices").path(0).path("message").path("content");
    }

    private String resolveApiKey() {
        String fromProperties = properties.getApiKey();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties;
        }
        return System.getenv("DEEPSEEK_API_KEY");
    }

    private String abbreviate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...";
    }
}
