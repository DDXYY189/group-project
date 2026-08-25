package com.example.group_demo.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 从 LLM 返回文本中稳健地提取 JSON 对象，兼容 markdown 代码块和多余说明。
 */
public final class TravelJsonParser {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TravelJsonParser() {
    }

    public static JsonNode extract(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("LLM 返回内容为空");
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline >= 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            int fence = trimmed.lastIndexOf("```");
            if (fence >= 0) {
                trimmed = trimmed.substring(0, fence);
            }
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM 输出中没有找到 JSON 对象: " + truncate(text));
        }
        try {
            return OBJECT_MAPPER.readTree(trimmed.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("LLM JSON 解析失败: " + e.getMessage(), e);
        }
    }

    public static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    public static List<String> textList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                result.add(item.asText());
            }
        }
        return result;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 200 ? text : text.substring(0, 200) + "...";
    }
}
