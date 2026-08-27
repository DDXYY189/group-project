package com.example.group_demo.mcp;

import com.example.group_demo.tool.ToolRegistry;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.McpJsonMapperSupplier;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class McpToolManager {

    private static final Logger log = LoggerFactory.getLogger(McpToolManager.class);

    private final McpProperties properties;
    private final ToolRegistry toolRegistry;
    private final Map<String, ServerState> states = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "mcp-client");
        thread.setDaemon(true);
        return thread;
    });

    public McpToolManager(McpProperties properties, ToolRegistry toolRegistry) {
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    @PostConstruct
    public void init() {
        if (properties.isEnabled()) {
            connectAll();
        }
    }

    public synchronized void reload() {
        states.values().forEach(this::unregisterBridges);
        states.values().forEach(ServerState::close);
        states.clear();
        if (properties.isEnabled()) {
            connectAll();
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.isEnabled());
        List<Map<String, Object>> servers = new ArrayList<>();
        for (ServerState state : states.values()) {
            servers.add(state.toMap());
        }
        result.put("servers", servers);
        result.put("tools", tools());
        return result;
    }

    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ServerState state : states.values()) {
            List<McpToolBridge> bridges = state.bridges;
            if (bridges == null) {
                continue;
            }
            for (McpToolBridge bridge : bridges) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("server", state.name);
                item.put("registeredName", bridge.name());
                item.put("description", bridge.description());
                item.put("parameters", bridge.parameters());
                tools.add(item);
            }
        }
        return tools;
    }

    @PreDestroy
    public void shutdown() {
        states.values().forEach(this::unregisterBridges);
        states.values().forEach(ServerState::close);
        states.clear();
        executor.shutdownNow();
    }

    private void unregisterBridges(ServerState state) {
        List<McpToolBridge> bridges = state.bridges;
        if (bridges == null) {
            return;
        }
        for (McpToolBridge bridge : bridges) {
            toolRegistry.unregister(bridge.name());
        }
    }

    private void connectAll() {
        for (Map.Entry<String, McpProperties.Server> entry : properties.getServers().entrySet()) {
            McpProperties.Server server = entry.getValue();
            if (server != null && server.isEnabled()) {
                executor.submit(() -> connect(new ServerConfig(entry.getKey(), server)));
            }
        }
    }

    private void connect(ServerConfig config) {
        String name = config.name();
        ServerState state = new ServerState(name, config.server().getType());
        states.put(name, state);
        try {
            McpClientTransport transport = buildTransport(config.server());
            McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("wechat-ilink-bot", "1.0.0"))
                .requestTimeout(properties.getRequestTimeout())
                .initializationTimeout(properties.getConnectTimeout())
                .build();
            client.initialize();
            List<McpSchema.Tool> remoteTools = client.listTools().tools();
            List<McpToolBridge> bridges = new ArrayList<>();
            McpToolCaller caller = (toolName, args) ->
                client.callTool(new McpSchema.CallToolRequest(toolName, args));
            for (McpSchema.Tool tool : remoteTools) {
                String registeredName = uniqueName(name, tool.name());
                McpToolBridge bridge = new McpToolBridge(name, registeredName, tool, caller);
                toolRegistry.register(bridge);
                bridges.add(bridge);
                log.info("MCP 工具已接入 server={} remote={} registered={}",
                    name, tool.name(), registeredName);
            }
            state.client = client;
            state.tools = remoteTools;
            state.bridges = bridges;
            state.error = null;
            log.info("MCP 服务连接成功 server={} tools={}", name, remoteTools.size());
        } catch (Exception e) {
            state.error = e.getMessage();
            unregisterBridges(state);
            state.close();
            log.warn("MCP 服务连接失败 server={}", name, e);
        }
    }

    private String uniqueName(String serverName, String toolName) {
        String base = McpToolNames.registeredName(serverName, toolName);
        String candidate = base;
        int index = 2;
        while (toolRegistry.find(candidate) != null) {
            candidate = base + "_" + index++;
            if (candidate.length() > 64) {
                candidate = candidate.substring(0, 64);
            }
        }
        return candidate;
    }

    private McpClientTransport buildTransport(McpProperties.Server server) {
        String type = server.getType() == null ? "stdio" : server.getType().toLowerCase();
        McpJsonMapper jsonMapper = jsonMapper();
        switch (type) {
            case "http":
            case "streamable":
                return HttpClientStreamableHttpTransport.builder(server.getUrl())
                    .jsonMapper(jsonMapper)
                    .requestBuilder(requestBuilder(server))
                    .build();
            case "sse":
                return HttpClientSseClientTransport.builder(server.getUrl())
                    .jsonMapper(jsonMapper)
                    .requestBuilder(requestBuilder(server))
                    .build();
            default:
                ServerParameters parameters = ServerParameters.builder(server.getCommand())
                    .args(server.getArgs())
                    .env(server.getEnv())
                    .build();
                return new StdioClientTransport(parameters, jsonMapper);
        }
    }

    private HttpRequest.Builder requestBuilder(McpProperties.Server server) {
        HttpRequest.Builder builder = HttpRequest.newBuilder();
        server.getHeaders().forEach(builder::header);
        return builder;
    }

    private static McpJsonMapper jsonMapper() {
        return ServiceLoader.load(McpJsonMapperSupplier.class).findFirst()
            .orElseThrow(() -> new IllegalStateException("未找到 MCP JSON mapper 实现"))
            .get();
    }

    private record ServerConfig(String name, McpProperties.Server server) {
    }

    private static final class ServerState {

        private final String name;
        private final String type;
        private volatile McpSyncClient client;
        private volatile List<McpSchema.Tool> tools = List.of();
        private volatile List<McpToolBridge> bridges = List.of();
        private volatile String error;

        private ServerState(String name, String type) {
            this.name = name;
            this.type = type;
            this.error = "连接中";
        }

        private Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", name);
            map.put("type", type);
            map.put("connected", error == null && client != null);
            map.put("toolCount", tools == null ? 0 : tools.size());
            map.put("error", error);
            return map;
        }

        private void close() {
            McpSyncClient current = client;
            client = null;
            tools = List.of();
            bridges = List.of();
            if (current != null) {
                try {
                    current.closeGracefully();
                } catch (Exception e) {
                    log.debug("关闭 MCP 客户端失败 server={}", name, e);
                }
            }
        }
    }
}
