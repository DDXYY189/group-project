package com.example.group_demo.mcp;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把远端 MCP 工具适配成 LLM 可调用的本地 BotTool。
 */
public class McpToolBridge implements BotTool {

    private static final Logger log = LoggerFactory.getLogger(McpToolBridge.class);

    private final String serverName;
    private final String registeredName;
    private final McpSchema.Tool remoteTool;
    private final McpToolCaller caller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public McpToolBridge(String serverName, String registeredName, McpSchema.Tool remoteTool,
                         McpToolCaller caller) {
        this.serverName = serverName;
        this.registeredName = registeredName;
        this.remoteTool = remoteTool;
        this.caller = caller;
    }

    @Override
    public String name() {
        return registeredName;
    }

    @Override
    public String description() {
        String description = remoteTool.description();
        if (description == null || description.isBlank()) {
            description = "MCP 远程工具";
        }
        return description + "（MCP 服务：" + serverName + "）";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = remoteTool.inputSchema();
        if (schema == null) {
            return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            );
        }
        return schema;
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        Map<String, Object> args = new LinkedHashMap<>();
        if (arguments != null && arguments.isObject()) {
            args = objectMapper.convertValue(arguments, new TypeReference<>() {
            });
        }
        McpSchema.CallToolResult result = caller.call(remoteTool.name(), args);
        return format(result);
    }

    String format(McpSchema.CallToolResult result) {
        if (result == null) {
            return "（MCP 工具无返回内容）";
        }
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(result.isError())) {
            text.append("[MCP 工具执行失败]\n");
        }
        if (result.structuredContent() != null) {
            try {
                text.append(objectMapper.writeValueAsString(result.structuredContent())).append("\n");
            } catch (Exception e) {
                log.warn("MCP 结构化结果序列化失败", e);
            }
        }
        List<McpSchema.Content> contents = result.content();
        if (contents != null) {
            for (McpSchema.Content content : contents) {
                if (content instanceof McpSchema.TextContent textContent
                    && textContent.text() != null && !textContent.text().isBlank()) {
                    text.append(textContent.text()).append("\n");
                } else if (content instanceof McpSchema.ImageContent imageContent) {
                    String data = imageContent.data();
                    text.append("[图片: mimeType=").append(imageContent.mimeType())
                        .append(", 数据长度=").append(data == null ? 0 : data.length()).append("]\n");
                } else if (content != null) {
                    text.append("[MCP 内容类型: ").append(content.type()).append("]\n");
                }
            }
        }
        String resultText = text.toString().strip();
        return resultText.isBlank() ? "（MCP 工具无返回内容）" : resultText;
    }
}
