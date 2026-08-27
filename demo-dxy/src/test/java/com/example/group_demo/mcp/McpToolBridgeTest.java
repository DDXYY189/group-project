package com.example.group_demo.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolBridgeTest {

    @Test
    void adaptsRemoteToolToLocalBotTool() throws Exception {
        McpSchema.Tool remoteTool = McpSchema.Tool.builder("remote_time")
            .description("获取远程时间")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            ))
            .build();
        McpToolCaller caller = (name, args, meta) -> {
            assertEquals("remote_time", name);
            assertTrue(args.isEmpty());
            assertEquals("u1", meta.get("userId"));
            return McpSchema.CallToolResult.builder()
                .addTextContent("10:30")
                .build();
        };

        McpToolBridge bridge = new McpToolBridge("demo", "mcp_demo_remote_time", remoteTool, caller);

        assertEquals("mcp_demo_remote_time", bridge.name());
        assertTrue(bridge.description().contains("MCP 服务：demo"));
        assertEquals("10:30", bridge.execute("u1", new ObjectMapper().readTree("{}")));
    }
}
