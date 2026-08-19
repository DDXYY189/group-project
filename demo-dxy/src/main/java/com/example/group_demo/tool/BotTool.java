package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * 微信机器人可暴露给 LLM 的自定义工具。
 */
public interface BotTool {

    String name();

    String description();

    Map<String, Object> parameters();

    String execute(String userId, JsonNode arguments);

    default Map<String, Object> jsonSchema() {
        return Map.of(
            "type", "function",
            "function", Map.of(
                "name", name(),
                "description", description(),
                "parameters", parameters()
            )
        );
    }
}
