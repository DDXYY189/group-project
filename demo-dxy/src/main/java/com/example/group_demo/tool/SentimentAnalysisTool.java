package com.example.group_demo.tool;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 情感分析工具 —— 微信机器人 BotTool。
 * <p>
 * 调用阿里云 DashScope 通义大模型，对用户输入的中文文本进行精细情感分析，
 * 返回「整体情感 | 情绪细分 | 置信度 | 关键词 | 理由」的稳定格式字符串。
 * <p>
 * 迁移自旧项目 {@code getSentimentAnalysis}，业务逻辑（系统提示词、JSON 解析容错、
 * 输出格式、错误消息）完整保留；底层调用改为本项目统一的 RestClient 兼容模式，
 * JSON 解析改用 Jackson，无需引入 DashScope 原生 SDK 依赖。
 */
@Service
public class SentimentAnalysisTool implements BotTool {

    private static final Logger log = LoggerFactory.getLogger(SentimentAnalysisTool.class);

    /** 情感分析专用系统提示词：强制输出严格 JSON，便于程序解析 */
    private static final String SENTIMENT_SYSTEM_PROMPT =
            "你是专业的中文情感分析引擎。对用户输入的文本进行精细情感分析，" +
            "只输出一个 JSON 对象，禁止输出任何其他文字、解释或 markdown 代码块标记。\n" +
            "JSON 字段如下：\n" +
            "- overall：整体情感类别，取值仅限 \"积极\"、\"中性\"、\"消极\" 之一\n" +
            "- subEmotion：情绪细分，例如 开心、夸赞、感谢、生气、抱怨、失望、担忧、嘲讽、平淡 等\n" +
            "- confidence：情绪置信度，0-100 的整数，越确定数值越高\n" +
            "- keywords：数组，列出文本中支撑该判断的关键词\n" +
            "- reason：简短理由（一句话），说明这些关键词如何支撑该判断\n" +
            "示例输入：你太笨了！\n" +
            "示例输出：{\"overall\":\"消极\",\"subEmotion\":\"生气\",\"confidence\":90,\"keywords\":[\"笨\"],\"reason\":\"“笨”带有侮辱性质，表达对对方智力的负面评价\"}";

    /** JSON 解析器，替代旧代码中的 Gson */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** DashScope API 密钥，从 application-local.properties 读取，禁止硬编码 */
    private final String apiKey;

    /** 情感分析所用大模型名称 */
    private final String textModel;

    /** 用于调用 DashScope 兼容模式接口的 HTTP 客户端 */
    private final RestClient restClient;

    /**
     * 构造函数注入配置，直接复用 llm.* 配置项（与主 LLM 共用同一个 DashScope 密钥）。
     *
     * @param apiKey  DashScope API Key，读取 llm.api-key
     * @param textModel 大模型名称，读取 llm.model，默认 qwen-plus
     * @param baseUrl  DashScope 兼容模式基址，读取 llm.base-url
     */
    public SentimentAnalysisTool(
            @Value("${llm.api-key:}") String apiKey,
            @Value("${llm.model:qwen-plus}") String textModel,
            @Value("${llm.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl) {
        this.apiKey = apiKey;
        this.textModel = textModel;
        this.restClient = RestClientFactory.builder().baseUrl(baseUrl).build();
    }

    @Override
    public String name() {
        return "analyze_sentiment";
    }

    @Override
    public String description() {
        return "对用户输入的中文文本进行精细情感分析，返回整体情感类别、情绪细分、"
            + "置信度、关键词和理由。当用户想分析一段文字的情感倾向、情绪或态度时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "text", Map.of(
                    "type", "string",
                    "description", "待进行情感分析的文本内容"
                )
            ),
            "required", List.of("text"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String text = arguments.path("text").asText("").trim();
        return getSentimentAnalysis(text);
    }

    /**
     * 情感分析核心方法，迁移自旧项目同名方法，业务逻辑完整保留。
     * <ol>
     *   <li>校验入参文本非空</li>
     *   <li>校验 API Key 已配置</li>
     *   <li>构建 system + user 消息，调用大模型</li>
     *   <li>解析大模型返回的情感 JSON，重新格式化为稳定字符串</li>
     * </ol>
     */
    public String getSentimentAnalysis(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "待分析文本为空，无法进行情感分析";
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("DashScope API Key 未配置, 无法调用大模型进行情感分析");
            return "[API Key 未配置, 无法调用大模型进行情感分析, 请在 application.properties 中设置 llm.api-key]";
        }

        try {
            // 构建请求消息：system 提示词 + user 待分析文本
            List<Map<String, Object>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", SENTIMENT_SYSTEM_PROMPT));
            messages.add(Map.of("role", "user", "content", text));

            Map<String, Object> requestBody = new LinkedHashMap<>();
            requestBody.put("model", textModel);
            requestBody.put("messages", messages);

            log.info("情感分析调用大模型: text={}", text);
            // 调用 DashScope 兼容模式 chat/completions 接口，响应体先取 String 再用 ObjectMapper 解析
            String responseBody = restClient.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody)
                .retrieve()
                .body(String.class);

            JsonNode response = objectMapper.readTree(responseBody);
            String content = response.path("choices").get(0).path("message").path("content").asText("");
            log.info("情感分析大模型原始返回: {}", content);

            return parseSentimentJson(content);
        } catch (Exception e) {
            log.error("情感分析异常: {}", e.getMessage(), e);
            return "[情感分析失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 解析大模型返回的情感分析 JSON，重新格式化为稳定字符串。
     * 容错：剥离可能存在的 ```json 代码块标记，提取首个 { ... } 对象。
     * <p>
     * 迁移自旧项目同名方法，解析逻辑完全一致，仅将 Gson API 替换为 Jackson API。
     */
    private String parseSentimentJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[大模型未返回有效情感分析结果]";
        }
        String raw = content.trim();
        // 兼容模型偶尔包裹 ```json ... ``` 的情况
        if (raw.startsWith("```")) {
            int firstBrace = raw.indexOf('{');
            int lastBrace = raw.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                raw = raw.substring(firstBrace, lastBrace + 1);
            }
        }

        try {
            JsonNode obj = objectMapper.readTree(raw);
            String overall = obj.has("overall") && !obj.get("overall").isNull()
                    ? obj.get("overall").asText() : "未知";
            String subEmotion = obj.has("subEmotion") && !obj.get("subEmotion").isNull()
                    ? obj.get("subEmotion").asText() : "未知";
            int confidence = obj.has("confidence") && !obj.get("confidence").isNull()
                    ? obj.get("confidence").asInt() : -1;
            String reason = obj.has("reason") && !obj.get("reason").isNull()
                    ? obj.get("reason").asText() : "";
            // 关键词列表拼接到理由中
            String keywordsStr = "";
            if (obj.has("keywords") && obj.get("keywords").isArray()) {
                List<String> kws = new ArrayList<>();
                obj.get("keywords").forEach(e -> kws.add(e.asText()));
                keywordsStr = String.join("、", kws);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("整体情感: ").append(overall)
              .append(" | 情绪细分: ").append(subEmotion)
              .append(" | 置信度: ").append(confidence >= 0 ? confidence + "%" : "未知");
            if (!keywordsStr.isEmpty()) {
                sb.append(" | 关键词: ").append(keywordsStr);
            }
            if (!reason.isEmpty()) {
                sb.append(" | 理由: ").append(reason);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("情感分析 JSON 解析失败, 原始返回: {}", content, e);
            // 解析失败时直接返回大模型原文，保证链路不中断
            return content;
        }
    }
}
