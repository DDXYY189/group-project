package com.example.group_demo.router;

import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.llm.LlmProperties;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.rag.RagProperties;
import com.example.group_demo.skill.SkillRegistry;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RAG 开启/关闭对比测试：同一问题在开启时 prompt 包含知识资料，
 * 关闭后 prompt 不包含知识资料。
 */
class RagToggleTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        requests.clear();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", this::handleChat);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String json = """
            {"choices":[{"message":{"content":"资料回复"}}]}
            """;
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Test
    void sameQuestionPromptsDifferWhenRagToggled() throws Exception {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String url = "jdbc:h2:mem:rag-toggle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        ToolRegistry tools = new ToolRegistry(List.of(tool("translate")));
        LlmService llm = new LlmService(properties, memory, tools);

        RagProperties ragProperties = new RagProperties();
        ragProperties.setEnabled(true);
        KeywordRagService rag = new KeywordRagService(ragProperties, List.of(
            new KnowledgeChunk("campus-1", "校庆", "学校校庆日是每年 11 月 18 日。")));
        MessageRouter router = new MessageRouter(new SkillRegistry(List.of()), llm, rag);

        String query = "学校校庆是什么时候";
        assertEquals("资料回复", router.route("u1", query));

        JsonNode enabledRequest = objectMapper.readTree(requests.get(0));
        String enabledSystem = enabledRequest.path("messages").get(0).path("content").asText();
        assertTrue(enabledSystem.contains("11 月 18 日"));
        assertTrue(enabledSystem.contains("参考资料"));

        rag.setEnabled(false);
        assertEquals("资料回复", router.route("u1", query));

        JsonNode disabledRequest = objectMapper.readTree(requests.get(1));
        String disabledSystem = disabledRequest.path("messages").get(0).path("content").asText();
        assertFalse(disabledSystem.contains("11 月 18 日"));
        assertFalse(disabledSystem.contains("参考资料"));
        assertEquals(2, requests.size());
    }

    private BotTool tool(String name) {
        return new BotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "测试工具 " + name;
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return name + " 执行成功";
            }
        };
    }
}
