package com.example.group_demo.mcp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpToolNamesTest {

    @Test
    void sanitizesRemoteToolNames() {
        String name = McpToolNames.registeredName("Demo Server", "remote-time");
        assertEquals("mcp_demo_server_remote_time", name);
        assertTrue(name.length() <= 64);
    }
}
