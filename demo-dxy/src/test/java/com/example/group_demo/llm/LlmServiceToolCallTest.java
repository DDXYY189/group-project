package com.example.group_demo.llm;

import com.example.group_demo.tool.BotTool;
import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmServiceToolCallTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicBoolean emptyFinal = new AtomicBoolean();
    private final AtomicReference<String> toolCallName = new AtomicReference<>("echo");

    @BeforeEach
    void startServer() throws IOException {
        emptyFinal.set(false);
        toolCallName.set("echo");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", this::handleChat);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(body);
        String json;
        int count = callCount.getAndIncrement();
        if (emptyFinal.get() && count > 0) {
            json = """
                {"choices":[{"message":{"content":null}}]}
                """;
        } else if (count == 0) {
            json = """
                {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"%s","arguments":"{\\"text\\":\\"你好\\"}"}}
                ]}}]}
                """.formatted(toolCallName.get());
        } else {
            json = """
                {"choices":[{"message":{"content":"工具结果：echo:你好"}}]}
                """;
        }
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

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
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return "echo:" + arguments.path("text").asText();
            }
        };
    }

    private BotTool relayTool() {
        return new BotTool() {
            @Override
            public String name() {
                return "relay";
            }

            @Override
            public String description() {
                return "直接回显工具结果";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return "热点列表：1. 第一条 2. 第二条";
            }

            @Override
            public boolean relayToUser() {
                return true;
            }
        };
    }

    @Test
    void runsToolCallLoopAndReturnsFinalReply() throws Exception {
        int port = server.getAddress().getPort();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setModel("qwen-plus");
        String url = "jdbc:h2:mem:tool-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        LlmService llm = new LlmService(properties, memory,
            new ToolRegistry(List.of(echoTool())));

        String reply = llm.chatWithTools("u1", "请调用工具");

        assertEquals("工具结果：echo:你好", reply);
        assertEquals(2, requests.size());

        JsonNode firstRequest = objectMapper.readTree(requests.get(0));
        assertEquals("echo", firstRequest.path("tools").get(0).path("function").path("name").asText());

        JsonNode secondMessages = objectMapper.readTree(requests.get(1)).path("messages");
        JsonNode toolMessage = secondMessages.get(secondMessages.size() - 1);
        assertEquals("tool", toolMessage.path("role").asText());
        assertEquals("call_1", toolMessage.path("tool_call_id").asText());
        assertEquals("echo:你好", toolMessage.path("content").asText());

        assertEquals(2, memory.history("u1").size());
    }

    @Test
    void fallsBackToToolResultWhenFinalReplyIsEmpty() throws Exception {
        emptyFinal.set(true);
        int port = server.getAddress().getPort();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setModel("qwen-plus");
        String url = "jdbc:h2:mem:tool-empty-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        LlmService llm = new LlmService(properties, memory,
            new ToolRegistry(List.of(echoTool())));

        String reply = llm.chatWithTools("u1", "请调用工具");

        assertEquals("echo:你好", reply);
        assertEquals(2, requests.size());
        assertEquals(2, memory.history("u1").size());
    }

    @Test
    void relaysToolResultDirectlyWithoutWaitingForLlm() throws Exception {
        toolCallName.set("relay");
        int port = server.getAddress().getPort();
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setModel("qwen-plus");
        String url = "jdbc:h2:mem:tool-relay-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        LlmService llm = new LlmService(properties, memory,
            new ToolRegistry(List.of(relayTool())));

        String reply = llm.chatWithTools("u1", "请给我热点");

        assertEquals("热点列表：1. 第一条 2. 第二条", reply);
        assertEquals(1, requests.size());
        assertEquals(2, memory.history("u1").size());
    }
}
