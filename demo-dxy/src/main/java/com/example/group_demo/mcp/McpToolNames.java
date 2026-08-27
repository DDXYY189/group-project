package com.example.group_demo.mcp;

public final class McpToolNames {

    private McpToolNames() {
    }

    public static String registeredName(String serverName, String toolName) {
        String name = "mcp_" + sanitize(serverName) + "_" + sanitize(toolName);
        if (name.length() > 64) {
            name = name.substring(0, 64);
        }
        return name;
    }

    static String sanitize(String value) {
        if (value == null) {
            return "server";
        }
        String normalized = value.toLowerCase().replaceAll("[^a-z0-9]+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "server" : normalized;
    }
}
