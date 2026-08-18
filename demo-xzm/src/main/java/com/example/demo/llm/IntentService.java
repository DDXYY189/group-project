package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 意图识别服务：用 LLM 分析用户消息，判断意图类型并提取参数。
 *
 * 意图类型：
 *   TEXT       — 仅文字回复（事实查询、计算、翻译、代码等不需要朗读的场景）
 *   TEXT_VOICE — 文字 + 语音回复（聊天、讲故事、解释、天气等需要朗读的场景）
 *   IMAGE      — 生成图片（提取图片描述）
 *   WEATHER    — 天气查询（提取城市名）
 *   VOICE      — 纯语音回复（用户明确要求只用语音）
 *   CLEAR      — 清除对话记忆
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final DashScopeClient client;
    private final LlmProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public IntentService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    public IntentResult classify(String userMessage) {
        String systemPrompt = """
                你是意图识别助手。根据用户消息判断意图，返回严格的 JSON（不要 markdown 代码块、不要任何额外文字）。

                意图类型：

                - IMAGE：用户想生成/画出/绘制图片。param 提取图片描述。
                  例："画一只猫" → {"intent":"IMAGE","param":"一只猫"}
                  例："帮我生成夕阳的图片" → {"intent":"IMAGE","param":"夕阳"}
                  例："/img 草地上的小狗" → {"intent":"IMAGE","param":"草地上的小狗"}
                  例："画个风景" → {"intent":"IMAGE","param":"风景"}

                - WEATHER：用户询问天气。param 提取城市名（未提及城市则填"北京"）。
                  例："上海天气怎么样" → {"intent":"WEATHER","param":"上海"}
                  例："今天天气如何" → {"intent":"WEATHER","param":"北京"}
                  例："深圳会下雨吗" → {"intent":"WEATHER","param":"深圳"}

                - VOICE：用户明确要求只用语音回复。param 提取要说的内容。
                  例："用语音说你好" → {"intent":"VOICE","param":"你好"}
                  例："语音回复：今天真开心" → {"intent":"VOICE","param":"今天真开心"}
                  例："只说一遍：早上好" → {"intent":"VOICE","param":"早上好"}

                - CLEAR：用户想清除/重置对话记忆。param 为空字符串。
                  例："清除记忆" → {"intent":"CLEAR","param":""}
                  例："/clear" → {"intent":"CLEAR","param":""}
                  例："重新开始" → {"intent":"CLEAR","param":""}

                - TEXT：适合纯文字回复的场景（数字、代码、翻译、事实查询、公式、技术问题等，不需要语音朗读）。param 为空。
                  例："1+1等于几" → {"intent":"TEXT","param":""}
                  例："Python怎么写hello world" → {"intent":"TEXT","param":""}
                  例："翻译hello" → {"intent":"TEXT","param":""}
                  例："圆周率是多少" → {"intent":"TEXT","param":""}
                  例："Java是编译型还是解释型" → {"intent":"TEXT","param":""}

                - TEXT_VOICE：需要语音朗读的对话（日常聊天、讲故事、解释概念、问候、情感表达等长回复）。param 为空。
                  例："你好" → {"intent":"TEXT_VOICE","param":""}
                  例："给我讲个故事" → {"intent":"TEXT_VOICE","param":""}
                  例："解释一下什么是AI" → {"intent":"TEXT_VOICE","param":""}
                  例："今天好累啊" → {"intent":"TEXT_VOICE","param":""}
                  例："你是谁" → {"intent":"TEXT_VOICE","param":""}

                判断规则（按优先级）：
                1. 含"画"、"生成图片"、"绘制"、"画图"或 /img → IMAGE
                2. 含"天气"、"下雨"、"气温"或"温度"且在询问天气 → WEATHER
                3. 含"语音说"、"语音回复"、"只用语音"、"只说"等明确要求 → VOICE
                4. 含"清除"、"重置"、"清空"、"重新开始"或 /clear → CLEAR
                5. 事实查询、计算、翻译、代码、技术问题 → TEXT
                6. 其余对话、聊天、问候、情感 → TEXT_VOICE

                只返回 JSON。""";

        try {
            Map<String, Object> request = Map.of(
                    "model", props.getIntent().getModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userMessage)
                    ),
                    "temperature", 0.1,
                    "max_tokens", 200
            );

            JsonNode resp = client.chatCompletions(request);
            String content = resp.path("choices").path(0).path("message").path("content").asText("").trim();
            log.info("意图识别原始返回: {}", content);

            return parseResult(content);
        } catch (Exception e) {
            log.warn("意图识别失败，回退为 TEXT_VOICE: {}", e.getMessage());
            return new IntentResult(IntentType.TEXT_VOICE, null);
        }
    }

    private IntentResult parseResult(String content) {
        try {
            String json = content;
            if (json.contains("```")) {
                int start = json.indexOf('{');
                int end = json.lastIndexOf('}');
                if (start >= 0 && end > start) {
                    json = json.substring(start, end + 1);
                }
            }
            JsonNode node = objectMapper.readTree(json);
            String intentStr = node.path("intent").asText("TEXT_VOICE").toUpperCase();
            String param = node.path("param").asText(null);

            IntentType type;
            try {
                type = IntentType.valueOf(intentStr);
            } catch (IllegalArgumentException e) {
                log.warn("未知意图类型: {} → 回退 TEXT_VOICE", intentStr);
                type = IntentType.TEXT_VOICE;
            }

            if (param != null && param.isBlank()) {
                param = null;
            }

            return new IntentResult(type, param);
        } catch (Exception e) {
            log.warn("意图 JSON 解析失败: {} → 回退 TEXT_VOICE", content);
            return new IntentResult(IntentType.TEXT_VOICE, null);
        }
    }

    public enum IntentType {
        TEXT,
        TEXT_VOICE,
        IMAGE,
        WEATHER,
        VOICE,
        CLEAR
    }

    public record IntentResult(IntentType type, String param) {}
}
