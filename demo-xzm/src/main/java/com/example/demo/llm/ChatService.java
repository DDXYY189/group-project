package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.example.demo.tools.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 文本对话服务：基于通义千问 OpenAI 兼容接口，按微信用户维护多轮对话历史。
 * 支持两种模式：
 * - chat()：普通对话（无工具）
 * - chatWithTools()：Function Calling 模式，LLM 可自主决定调用工具
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DashScopeClient client;
    private final LlmProperties props;
    private final ToolRegistry toolRegistry;
    private final Map<String, List<Map<String, Object>>> histories = new ConcurrentHashMap<>();

    public ChatService(DashScopeClient client, LlmProperties props, ToolRegistry toolRegistry) {
        this.client = client;
        this.props = props;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 发送一次对话并返回助手回复文本。
     * 每次请求都会把实时日期注入 system 消息，保证 LLM 知道"今天"是哪天；
     * 历史消息按 userId 隔离保留，实现真正的多轮上下文。
     */
    public String chat(String userId, String userText) {
        List<Map<String, Object>> history = histories.computeIfAbsent(userId, k -> new ArrayList<>());

        rebuildSystemMessage(history);

        history.add(Map.of("role", "user", "content", userText));

        List<Map<String, Object>> messages = new ArrayList<>(history);
        Map<String, Object> request = Map.of(
                "model", props.getChat().getModel(),
                "messages", messages
        );

        try {
            log.info(">>> 请求 userId={} 消息条数={}", userId, messages.size());
            JsonNode resp = client.chatCompletions(request);
            String reply = resp.path("choices").path(0).path("message").path("content").asText("").trim();
            if (reply.isEmpty()) {
                reply = "（模型未返回内容）";
            }
            log.info("<<< 回复 userId={}: {}", userId, reply);
            history.add(Map.of("role", "assistant", "content", reply));
            trimHistory(history);
            return reply;
        } catch (Exception e) {
            log.error("对话调用失败 userId={}: {}", userId, e.getMessage());
            history.remove(history.size() - 1);
            return "调用大模型失败: " + e.getMessage();
        }
    }

    /**
     * Function Calling 对话：LLM 自主决定是否调用工具。
     *
     * 工作流程：
     * 1. 发送 user 消息 + tools 列表给 LLM
     * 2. LLM 返回 tool_calls 或直接回复
     * 3. 如果有 tool_calls，执行每个工具，把结果加入消息历史
     * 4. 再次调用 LLM，让它根据工具结果生成自然语言回复
     *
     * @param userId   微信用户 ID
     * @param userText 用户消息
     * @return 最终回复文本
     */
    public String chatWithTools(String userId, String userText) {
        List<Map<String, Object>> history = histories.computeIfAbsent(userId, k -> new ArrayList<>());

        rebuildSystemMessage(history);
        history.add(Map.of("role", "user", "content", userText));

        List<Map<String, Object>> messages = new ArrayList<>(history);
        List<Map<String, Object>> toolsSchema = toolRegistry.getToolsSchema();

        Map<String, Object> request = new HashMap<>();
        request.put("model", props.getChat().getModel());
        request.put("messages", messages);
        request.put("tools", toolsSchema);

        try {
            log.info(">>> Function Calling 请求 userId={} 消息条数={} 工具数={}",
                    userId, messages.size(), toolsSchema.size());

            JsonNode resp = client.chatCompletions(request);
            JsonNode message = resp.path("choices").path(0).path("message");
            JsonNode toolCalls = message.path("tool_calls");

            if (toolCalls.isArray() && toolCalls.size() > 0) {
                String assistantContent = message.path("content").asText("");
                Map<String, Object> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                if (!assistantContent.isEmpty()) {
                    assistantMsg.put("content", assistantContent);
                }
                assistantMsg.put("tool_calls", objectMapper(message));
                history.add(assistantMsg);

                for (JsonNode tc : toolCalls) {
                    String callId = tc.path("id").asText();
                    String fnName = tc.path("function").path("name").asText();
                    String fnArgs = tc.path("function").path("arguments").asText("{}");

                    log.info("LLM 调用工具: {} args={}", fnName, fnArgs);
                    String toolResult = toolRegistry.executeTool(fnName, fnArgs);

                    history.add(Map.of(
                            "role", "tool",
                            "tool_call_id", callId,
                            "content", toolResult
                    ));
                }

                trimHistory(history);

                List<Map<String, Object>> messages2 = new ArrayList<>(history);
                Map<String, Object> request2 = new HashMap<>();
                request2.put("model", props.getChat().getModel());
                request2.put("messages", messages2);
                request2.put("tools", toolsSchema);

                log.info(">>> 第二轮请求（工具结果）userId={} 消息条数={}", userId, messages2.size());
                JsonNode resp2 = client.chatCompletions(request2);
                String reply = resp2.path("choices").path(0).path("message").path("content").asText("").trim();
                if (reply.isEmpty()) {
                    reply = "（模型未返回内容）";
                }
                log.info("<<< 工具回复 userId={}: {}", userId, reply);
                history.add(Map.of("role", "assistant", "content", reply));
                trimHistory(history);
                return reply;
            } else {
                String reply = message.path("content").asText("").trim();
                if (reply.isEmpty()) {
                    reply = "（模型未返回内容）";
                }
                log.info("<<< 直接回复 userId={}: {}", userId, reply);
                history.add(Map.of("role", "assistant", "content", reply));
                trimHistory(history);
                return reply;
            }
        } catch (Exception e) {
            log.error("Function Calling 对话失败 userId={}: {}", userId, e.getMessage());
            history.remove(history.size() - 1);
            return "调用大模型失败: " + e.getMessage();
        }
    }

    private Map<String, Object> objectMapper(JsonNode message) {
        Map<String, Object> result = new HashMap<>();
        result.put("role", "assistant");
        String content = message.path("content").asText("");
        if (!content.isEmpty()) {
            result.put("content", content);
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        JsonNode toolCalls = message.path("tool_calls");
        if (toolCalls.isArray()) {
            for (JsonNode tc : toolCalls) {
                Map<String, Object> call = new HashMap<>();
                call.put("id", tc.path("id").asText());
                call.put("type", tc.path("type").asText("function"));
                call.put("function", Map.of(
                        "name", tc.path("function").path("name").asText(),
                        "arguments", tc.path("function").path("arguments").asText("")
                ));
                calls.add(call);
            }
        }
        result.put("tool_calls", calls);
        return result;
    }

    /**
     * 流式对话：通过 SSE 逐 token 返回文本，降低首字延迟。
     * onToken 回调在每个文本片段到达时被调用，可用于实时触发 TTS。
     *
     * @param userId    微信用户 ID
     * @param userText  用户消息
     * @param onToken   每收到一个文本片段时的回调
     * @return 完整的助手回复文本
     */
    public String chatStream(String userId, String userText, Consumer<String> onToken) {
        List<Map<String, Object>> history = histories.computeIfAbsent(userId, k -> new ArrayList<>());
        rebuildSystemMessage(history);
        history.add(Map.of("role", "user", "content", userText));

        List<Map<String, Object>> messages = new ArrayList<>(history);
        Map<String, Object> request = new HashMap<>();
        request.put("model", props.getChat().getModel());
        request.put("messages", messages);
        request.put("stream", true);

        try {
            log.info(">>> 流式请求 userId={} 消息条数={}", userId, messages.size());
            StringBuilder fullReply = new StringBuilder();
            client.chatCompletionsStream(request, token -> {
                fullReply.append(token);
                onToken.accept(token);
            });

            String reply = fullReply.toString().trim();
            if (reply.isEmpty()) {
                reply = "（模型未返回内容）";
            }
            log.info("<<< 流式回复 userId={}: {}", userId, reply);
            history.add(Map.of("role", "assistant", "content", reply));
            trimHistory(history);
            return reply;
        } catch (Exception e) {
            log.error("流式对话调用失败 userId={}: {}", userId, e.getMessage());
            history.remove(history.size() - 1);
            return "调用大模型失败: " + e.getMessage();
        }
    }

    /**
     * 重建 system 消息：把配置的系统提示词与当前日期拼接，放在历史最前。
     * 每次都更新，保证 LLM 知道"今天"的准确日期。
     */
    private void rebuildSystemMessage(List<Map<String, Object>> history) {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(DateTimeFormatter.ofPattern("yyyy年M月d日"));
        String base = props.getChat().getSystemPrompt();
        String sysContent = base.isBlank()
                ? "当前日期：" + today
                : base + "\n\n当前日期：" + today;

        if (!history.isEmpty() && "system".equals(history.get(0).get("role"))) {
            history.set(0, Map.of("role", "system", "content", sysContent));
        } else {
            history.add(0, Map.of("role", "system", "content", sysContent));
        }
    }

    private void trimHistory(List<Map<String, Object>> history) {
        // 预留：system(1) + maxHistory*2(一问一答)
        int max = props.getChat().getMaxHistory() * 2 + 1;
        while (history.size() > max) {
            // system 始终保留在 index 0，从 index 1 开始删除最早的对话
            history.remove(1);
        }
    }

    public void clearHistory(String userId) {
        histories.remove(userId);
    }
}
