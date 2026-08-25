package com.example.group_demo.travel;

import com.example.group_demo.llm.LlmProperties;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.rag.RagProperties;
import com.example.group_demo.tool.BotTool;
import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TravelAgentServiceTest {

    private static final String EXTRACT_JSON = """
        {"destination":"上海","days":3,"budget":"5000","dates":"4月1日-3日",
         "travelers":"2人","preferences":"美食、夜景","question":null}
        """;

    private static final String PLAN_JSON = """
        {
          "destination": "上海",
          "days": 3,
          "dates": ["4月1日", "4月2日", "4月3日"],
          "budget": {"total": "5000", "items": [{"name": "交通", "amount": "1200"}]},
          "itinerary": [
            {
              "day": 1,
              "title": "外滩与老城厢",
              "weather": "晴 20℃",
              "schedule": [
                {"time": "09:00", "item": "抵达上海入住酒店"},
                {"time": "12:00", "item": "城隍庙午餐"},
                {"time": "15:00", "item": "外滩漫步"},
                {"time": "19:00", "item": "浦江夜游"}
              ],
              "meals": "本帮菜",
              "hotel": "南京东路附近",
              "notes": "提前预约"
            }
          ],
          "tips": ["提前订票", "注意天气", "错峰出行"],
          "mustDos": ["预订外滩门票", "下载地铁APP"],
          "heroPrompt": "上海外滩夜景插画"
        }
        """;

    private HttpServer server;
    private final AtomicInteger llmCallCount = new AtomicInteger();
    private final AtomicInteger todoCount = new AtomicInteger();
    private Path pageDir;
    private String extractJson;
    private String planJson;

    @BeforeEach
    void setUp() throws IOException {
        extractJson = EXTRACT_JSON;
        planJson = PLAN_JSON;
        llmCallCount.set(0);
        todoCount.set(0);
        pageDir = Files.createTempDirectory("travel-agent-test-");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", this::handleChat);
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
        try (var paths = Files.walk(pageDir)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // test cleanup
                }
            });
        }
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        int count = llmCallCount.getAndIncrement();
        String json = count == 0 ? extractJson : planJson;
        String body = """
            {"choices":[{"message":{"content":%s}}]}
            """.formatted(jsonToJsonString(json));
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String jsonToJsonString(String json) {
        try {
            return new ObjectMapper().writeValueAsString(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private TravelAgentService newService() {
        LlmProperties llmProperties = new LlmProperties();
        llmProperties.setApiKey("test-key");
        llmProperties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        llmProperties.setModel("test-model");
        LlmService llmService = new LlmService(llmProperties, null);

        ToolRegistry registry = new ToolRegistry(List.of(
            weatherTool(), searchTool(), todoTool()));

        RagProperties ragProperties = new RagProperties();
        ragProperties.setEnabled(true);
        ragProperties.setTopK(3);
        KeywordRagService ragService = new KeywordRagService(ragProperties, List.of(
            new KnowledgeChunk("travel-1", "旅行规划知识库",
                "上海旅行攻略：外滩适合傍晚游览，城隍庙小吃丰富，热门景点提前预约门票。")));

        TravelProperties travelProperties = new TravelProperties();
        travelProperties.setPageDir(pageDir.toString());
        travelProperties.setPageBaseUrl("http://localhost:8080/api/trips");
        travelProperties.setGenerateImage(false);
        travelProperties.setGenerateVoice(false);
        return new TravelAgentService(llmService, registry, ragService, null, null,
            new TravelPageRenderer(), travelProperties);
    }

    @Test
    void completesFullAgentLoop() {
        TravelAgentResult result = newService().run("demo", "帮我规划上海3日游，预算5000");

        assertEquals("done", result.status());
        assertNotNull(result.pageId());
        assertTrue(result.htmlUrl().endsWith(".html"));
        assertTrue(result.steps().size() >= 6);
        assertTrue(result.reply().contains("网页版完整方案"));
        assertTrue(result.reply().contains("Agent 自动执行步骤"));
        assertTrue(Files.isRegularFile(pageDir.resolve(result.pageId() + ".html")));
        assertEquals(3, todoCount.get());
        assertEquals(3, result.todoCount());
    }

    @Test
    void asksForMissingInformationBeforeRunningTools() {
        extractJson = """
            {"destination":null,"days":null,"budget":null,"dates":null,
             "travelers":null,"preferences":null,
             "question":"你想去哪个城市，计划玩几天？"}
            """;

        TravelAgentResult result = newService().run("demo", "帮我规划一次旅行");

        assertEquals("need_more_info", result.status());
        assertEquals("你想去哪个城市，计划玩几天？", result.question());
        assertEquals(0, todoCount.get());
        assertEquals(1, llmCallCount.get());
    }

    private BotTool weatherTool() {
        return new BotTool() {
            @Override
            public String name() {
                return "query_weather";
            }

            @Override
            public String description() {
                return "天气";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return "上海今日晴，20-26℃，适合出行";
            }
        };
    }

    private BotTool searchTool() {
        return new BotTool() {
            @Override
            public String name() {
                return "web_search";
            }

            @Override
            public String description() {
                return "搜索";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return "外滩：傍晚最佳；城隍庙：小吃丰富；南京东路：住宿选择多；地铁网络便利。";
            }
        };
    }

    private BotTool todoTool() {
        return new BotTool() {
            @Override
            public String name() {
                return "manage_todo";
            }

            @Override
            public String description() {
                return "待办";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object");
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                todoCount.incrementAndGet();
                return "已添加待办 #" + todoCount.get();
            }
        };
    }
}
