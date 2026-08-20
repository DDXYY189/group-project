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

    /**
     * 返回 true 时，工具结果直接作为机器人回复发送，不再交给 LLM 二次总结。
     */
    default boolean relayToUser() {
        return false;
    }

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
