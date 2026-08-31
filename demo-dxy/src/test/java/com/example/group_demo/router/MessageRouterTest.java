package com.example.group_demo.router;

import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.llm.LlmProperties;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.RagProperties;
import com.example.group_demo.skill.Skill;
import com.example.group_demo.skill.SkillRegistry;
import com.example.group_demo.skill.travel.TravelSkill;
import com.example.group_demo.tool.BotTool;
import com.example.group_demo.tool.ToolRegistry;
import com.example.group_demo.travel.TravelAgentResult;
import com.example.group_demo.travel.TravelAgentService;
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

class MessageRouterTest {

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
            {"choices":[{"message":{"content":"旅行助手回复"}}]}
            """;
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private LlmService newLlm(ToolRegistry tools) {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        String url = "jdbc:h2:mem:router-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        return new LlmService(properties, memory, tools);
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

    private KeywordRagService disabledRag() {
        RagProperties properties = new RagProperties();
        properties.setEnabled(false);
        return new KeywordRagService(properties, List.of());
    }

    @Test
    void routesTravelSkillToLongTaskAgentWithoutLlmCall() {
        TravelSkill travel = new TravelSkill(new TravelAgentService(
            null, null, null, null, null, null, null, null) {
            @Override
            public TravelAgentResult run(String userId, String goal) {
                return TravelAgentResult.error("agent:" + goal);
            }
        });
        MessageRouter router = new MessageRouter(
            new SkillRegistry(List.of(travel)), newLlm(new ToolRegistry(List.of())), disabledRag());

        String reply = router.route("u1", "帮我规划成都三日游行程");

        assertTrue(reply.contains("agent:帮我规划成都三日游行程"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void routesNaturalTravelPromptToLongTaskAgent() {
        TravelSkill travel = new TravelSkill(new TravelAgentService(
            null, null, null, null, null, null, null, null) {
            @Override
            public TravelAgentResult run(String userId, String goal) {
                return TravelAgentResult.error("agent:" + goal);
            }
        });
        MessageRouter router = new MessageRouter(
            new SkillRegistry(List.of(travel)), newLlm(new ToolRegistry(List.of())), disabledRag());

        String reply = router.route("u1",
            "预算5000，帮我规划4月1-3号上海3日游，两个人，喜欢美食和夜景");

        assertTrue(reply.contains("agent:预算5000，帮我规划4月1-3号上海3日游"));
        assertTrue(requests.isEmpty());
    }

    @Test
    void fallsBackToNormalChatWithAllToolsWhenNoSkillMatches() throws Exception {
        ToolRegistry tools = new ToolRegistry(List.of(
            tool("web_search"), tool("query_weather"), tool("manage_todo"),
            tool("translate"), tool("get_hot_news")));
        MessageRouter router = new MessageRouter(
            new SkillRegistry(List.of(new TravelSkill(null))), newLlm(tools), disabledRag());

        String reply = router.route("u1", "今天心情怎么样");

        assertEquals("旅行助手回复", reply);
        JsonNode first = objectMapper.readTree(requests.get(0));
        String systemContent = first.path("messages").get(0).path("content").asText();
        assertFalse(systemContent.contains("旅行规划助手"));
        List<String> toolNames = new ArrayList<>();
        first.path("tools").forEach(item ->
            toolNames.add(item.path("function").path("name").asText()));
        assertEquals(5, toolNames.size());
    }

    @Test
    void routesDirectSkillWithoutLlmCall() {
        Skill direct = new Skill() {
            @Override
            public String name() {
                return "echo_skill";
            }

            @Override
            public String description() {
                return "回显技能";
            }

            @Override
            public List<String> keywords() {
                return List.of("回显");
            }

            @Override
            public boolean directReply() {
                return true;
            }

            @Override
            public String execute(String userId, String text) {
                return "回显结果:" + text;
            }
        };
        ToolRegistry tools = new ToolRegistry(List.of(tool("translate")));
        MessageRouter router = new MessageRouter(
            new SkillRegistry(List.of(direct)), newLlm(tools), disabledRag());

        String reply = router.route("u1", "回显一下");

        assertEquals("回显结果:回显一下", reply);
        assertTrue(requests.isEmpty());
    }
}
