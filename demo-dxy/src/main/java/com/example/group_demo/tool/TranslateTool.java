package com.example.group_demo.tool;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 翻译工具：调用 MyMemory 免费 API 实现多语言翻译。
 * 无需 API Key，每日 5000 词免费额度。
 */
@Service
public class TranslateTool implements BotTool {

    private static final Logger log = LoggerFactory.getLogger(TranslateTool.class);
    private static final String BASE_URL = "https://api.mymemory.translated.net/get";

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TranslateTool() {
        this.restClient = RestClientFactory.builder()
                .baseUrl(BASE_URL)
                .build();
    }

    @Override
    public String name() {
        return "translate";
    }

    @Override
    public String description() {
        return "将文本从一种语言翻译为另一种语言。支持中文(zh)、英语(en)、日语(ja)、韩语(ko)、法语(fr)、德语(de)、西班牙语(es)、俄语(ru)等。当用户要求翻译、译为某种语言时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "text", Map.of(
                    "type", "string",
                    "description", "要翻译的原文文本"
                ),
                "source_lang", Map.of(
                    "type", "string",
                    "description", "源语言代码，如 en、zh、ja、ko、fr、de、es、ru",
                    "enum", List.of("en", "zh", "ja", "ko", "fr", "de", "es", "ru", "auto")
                ),
                "target_lang", Map.of(
                    "type", "string",
                    "description", "目标语言代码，如 en、zh、ja、ko、fr、de、es、ru",
                    "enum", List.of("en", "zh", "ja", "ko", "fr", "de", "es", "ru")
                )
            ),
            "required", List.of("text", "target_lang"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String text = arguments.path("text").asText("").trim();
        String targetLang = arguments.path("target_lang").asText("").trim();
        String sourceLang = arguments.path("source_lang").asText("auto").trim();

        if (text.isEmpty()) {
            throw new IllegalArgumentException("缺少 text 参数");
        }
        if (targetLang.isEmpty()) {
            throw new IllegalArgumentException("缺少 target_lang 参数");
        }
        if (text.length() > 500) {
            throw new IllegalArgumentException("单次翻译不能超过500字符，当前长度: " + text.length());
        }

        String resolvedSource = "auto".equals(sourceLang) ? detectLang(text) : sourceLang;
        String langPair = toMyMemoryLang(resolvedSource) + "|" + toMyMemoryLang(targetLang);

        log.info("翻译请求 userId={} source={} target={} text={}", userId, sourceLang, targetLang, text);

        String responseJson = restClient.get()
            .uri(uriBuilder -> uriBuilder
                .queryParam("q", text)
                .queryParam("langpair", langPair)
                .build())
            .retrieve()
            .body(String.class);

        try {
            JsonNode root = objectMapper.readTree(responseJson);
            int status = root.path("responseStatus").asInt(200);
            if (status != 200) {
                String detail = root.path("responseDetails").asText("未知错误");
                throw new RuntimeException("翻译API返回错误: " + detail);
            }
            String translated = root.path("responseData").path("translatedText").asText("");
            if (translated.isEmpty()) {
                throw new RuntimeException("翻译API未返回翻译结果");
            }
            log.info("翻译成功 userId={} result={}", userId, translated);
            return translated;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("解析翻译API响应失败: " + e.getMessage());
        }
    }

    /**
     * 将内部语言代码转为 MyMemory API 要求的格式（大写，中文用 ZH-CN）。
     */
    private String toMyMemoryLang(String lang) {
        if ("zh".equalsIgnoreCase(lang)) {
            return "ZH-CN";
        }
        return lang.toUpperCase();
    }

    /**
     * 简单语言检测：根据字符范围判断中文/日文/韩文，否则默认英文。
     */
    private String detectLang(String text) {
        for (char c : text.toCharArray()) {
            if (c >= 0x4E00 && c <= 0x9FFF) return "zh";
            if (c >= 0x3040 && c <= 0x309F) return "ja";
            if (c >= 0x30A0 && c <= 0x30FF) return "ja";
            if (c >= 0xAC00 && c <= 0xD7AF) return "ko";
        }
        return "en";
    }
}
