package com.example.group_demo.mcp;

import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * 同应用内的 MCP 演示 Server：只暴露少量 MCP 专用演示工具。
 */
@Service
@ConditionalOnProperty(prefix = "mcp", name = "demo-server-enabled", havingValue = "true",
    matchIfMissing = true)
public class McpDemoServer {

    private static final Logger log = LoggerFactory.getLogger(McpDemoServer.class);

    private HttpServletStreamableServerTransportProvider provider;
    private McpSyncServer server;

    @PostConstruct
    public void start() {
        McpJsonMapper jsonMapper = ServiceLoader.load(McpJsonMapperSupplier.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到 MCP JSON mapper 实现"))
            .get();
        provider = HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(jsonMapper)
            .mcpEndpoint("/mcp/demo")
            .build();

        List<McpServerFeatures.SyncToolSpecification> tools = new ArrayList<>();
        tools.add(echoToolSpec());
        tools.add(currentTimeToolSpec());
        tools.add(addToolSpec());

        server = McpServer.sync(provider)
            .serverInfo("group-demo-mcp", "1.0.0")
            .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
            .tools(tools)
            .build();
        log.info("MCP 演示 Server 已启动，暴露工具数={}", tools.size());
    }

    public HttpServletStreamableServerTransportProvider provider() {
        return provider;
    }

    public List<McpSchema.Tool> listTools() {
        return server == null ? List.of() : server.listTools();
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.closeGracefully();
        }
    }

    private McpServerFeatures.SyncToolSpecification echoToolSpec() {
        McpSchema.Tool schema = McpSchema.Tool.builder("demo_echo")
            .description("回显文本，用于演示 MCP 工具调用。")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "text", Map.of("type", "string", "description", "要回显的文本")
                ),
                "required", List.of("text"),
                "additionalProperties", false
            ))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(schema)
            .callHandler((exchange, request) -> {
                Object text = request.arguments() == null ? null : request.arguments().get("text");
                String result = text == null ? "" : String.valueOf(text);
                return McpSchema.CallToolResult.builder().addTextContent("MCP echo: " + result).build();
            })
            .build();
    }

    private McpServerFeatures.SyncToolSpecification currentTimeToolSpec() {
        McpSchema.Tool schema = McpSchema.Tool.builder("demo_current_time")
            .description("返回当前日期和时间，用于演示 MCP 工具调用。")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
            ))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(schema)
            .callHandler((exchange, request) -> {
                String time = LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                return McpSchema.CallToolResult.builder().addTextContent("当前时间：" + time).build();
            })
            .build();
    }

    private McpServerFeatures.SyncToolSpecification addToolSpec() {
        McpSchema.Tool schema = McpSchema.Tool.builder("demo_add")
            .description("计算两个数字的和，用于演示 MCP 工具调用。")
            .inputSchema(Map.of(
                "type", "object",
                "properties", Map.of(
                    "a", Map.of("type", "number", "description", "第一个数字"),
                    "b", Map.of("type", "number", "description", "第二个数字")
                ),
                "required", List.of("a", "b"),
                "additionalProperties", false
            ))
            .build();
        return McpServerFeatures.SyncToolSpecification.builder()
            .tool(schema)
            .callHandler((exchange, request) -> {
                Map<String, Object> args = request.arguments();
                double a = numberArg(args, "a");
                double b = numberArg(args, "b");
                double sum = a + b;
                String result = sum == Math.rint(sum) && !Double.isInfinite(sum)
                    ? String.valueOf((long) sum) : String.valueOf(sum);
                return McpSchema.CallToolResult.builder().addTextContent(a + " + " + b + " = " + result).build();
            })
            .build();
    }

    private double numberArg(Map<String, Object> args, String name) {
        Object value = args == null ? null : args.get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(name + " 必须是数字");
            }
        }
        throw new IllegalArgumentException("缺少参数 " + name);
    }
}
