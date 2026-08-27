package com.example.group_demo.mcp;

import io.modelcontextprotocol.spec.McpSchema;

import java.util.Map;

@FunctionalInterface
public interface McpToolCaller {

    McpSchema.CallToolResult call(String toolName, Map<String, Object> arguments,
                                  Map<String, Object> meta);
}
