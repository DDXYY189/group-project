package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
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
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    private final DashScopeClient client;
    private final LlmProperties props;
    private final Map<String, List<Map<String, String>>> histories = new ConcurrentHashMap<>();

    public ChatService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 发送一次对话并返回助手回复文本。
     * 每次请求都会把实时日期注入 system 消息，保证 LLM 知道"今天"是哪天；
     * 历史消息按 userId 隔离保留，实现真正的多轮上下文。
     */
    public String chat(String userId, String userText) {
        List<Map<String, String>> history = histories.computeIfAbsent(userId, k -> new ArrayList<>());

        // 每轮都重建 system 消息：包含配置的提示词 + 当前日期，确保 LLM 始终知道今天是哪天
        rebuildSystemMessage(history);

        history.add(Map.of("role", "user", "content", userText));

        // 构造请求时用历史副本，避免序列化过程中 list 被并发修改
        List<Map<String, String>> messages = new ArrayList<>(history);
        Map<String, Object> request = Map.of(
                "model", props.getChat().getModel(),
                "messages", messages
        );

        try {
            log.info(">>> 请求 userId={} 消息条数={} 历史={}", userId, messages.size(), messages);
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
     * 流式对话：通过 SSE 逐 token 返回文本，降低首字延迟。
     * onToken 回调在每个文本片段到达时被调用，可用于实时触发 TTS。
     *
     * @param userId    微信用户 ID
     * @param userText  用户消息
     * @param onToken   每收到一个文本片段时的回调
     * @return 完整的助手回复文本
     */
    public String chatStream(String userId, String userText, Consumer<String> onToken) {
        List<Map<String, String>> history = histories.computeIfAbsent(userId, k -> new ArrayList<>());
        rebuildSystemMessage(history);
        history.add(Map.of("role", "user", "content", userText));

        List<Map<String, String>> messages = new ArrayList<>(history);
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
    private void rebuildSystemMessage(List<Map<String, String>> history) {
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

    private void trimHistory(List<Map<String, String>> history) {
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
