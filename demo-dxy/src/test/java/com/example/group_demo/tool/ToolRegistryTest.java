package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    private BotTool echoTool() {
        return new BotTool() {
            @Override
            public String name() {
                return "echo";
            }

            @Override
            public String description() {
                return "回显文本";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of(
                    "type", "object",
                    "properties", Map.of("text", Map.of("type", "string")),
                    "required", List.of("text")
                );
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return "echo:" + arguments.path("text").asText();
            }
        };
    }

    @Test
    void buildsJsonSchemas() {
        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));

        assertEquals(List.of("echo"), registry.names());
        assertEquals("function", registry.jsonSchemas().get(0).get("type"));
    }

    @Test
    void executesToolAndReturnsErrors() {
        ToolRegistry registry = new ToolRegistry(List.of(echoTool()));

        assertEquals("echo:你好", registry.execute("u1", "echo", "{\"text\":\"你好\"}"));
        assertTrue(registry.execute("u1", "missing", "{}").contains("工具不存在"));
        assertTrue(registry.execute("u1", "echo", "{bad json").contains("工具调用失败"));
    }

    @Test
    void rejectsDuplicateNames() {
        assertThrows(IllegalStateException.class, () -> new ToolRegistry(List.of(echoTool(), echoTool())));
    }
}
