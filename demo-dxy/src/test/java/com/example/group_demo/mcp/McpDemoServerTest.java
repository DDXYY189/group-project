package com.example.group_demo.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpDemoServerTest {

    @Test
    void exposesOnlyThreeDemoTools() {
        McpDemoServer server = new McpDemoServer();
        server.start();
        try {
            List<McpSchema.Tool> tools = server.listTools();
            assertEquals(3, tools.size());
            assertTrue(tools.stream().anyMatch(tool -> "demo_echo".equals(tool.name())));
            assertTrue(tools.stream().anyMatch(tool -> "demo_current_time".equals(tool.name())));
            assertTrue(tools.stream().anyMatch(tool -> "demo_add".equals(tool.name())));
        } finally {
            server.stop();
        }
    }
}
