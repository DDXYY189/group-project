package com.example.group_demo.tool.chain;

import com.example.group_demo.tool.BotTool;
import com.example.group_demo.tool.TodoService;
import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolChainServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void secondStepArgumentComesFromFirstStepResult() throws Exception {
        AtomicReference<JsonNode> todoArguments = new AtomicReference<>();
        ToolRegistry registry = new ToolRegistry(List.of(
            echoTool("echo_weather", "北京晴"),
            recordingTodoTool(todoArguments)
        ));
        ToolChainService service = new ToolChainService(registry, List.of(chain("test_chain", List.of(
            new ToolChain.Step("echo_weather", "{\"location\": \"{{input.location}}\"}"),
            new ToolChain.Step("add_todo", "{\"action\": \"add\", \"text\": \"{{prev.result}}\"}")
        ))));

        String result = service.run("u1", "test_chain",
            objectMapper.readTree("{\"location\":\"北京\"}"));

        assertEquals("北京晴", todoArguments.get().path("text").asText());
        assertTrue(result.contains("链式流程执行成功"));
        assertTrue(result.contains("echo_weather"));
        assertTrue(result.contains("add_todo"));
    }

    @Test
    void threeStepChainDerivesSearchQueryFromHotNewsFirstItem() throws Exception {
        AtomicReference<JsonNode> searchArguments = new AtomicReference<>();
        AtomicReference<JsonNode> todoArguments = new AtomicReference<>();
        ToolRegistry registry = new ToolRegistry(List.of(
            echoTool("get_hot_news", "每日热点（今日）Top 2：\n1. AI 头条\n2. 科技头条"),
            recordingSearchTool(searchArguments),
            recordingTodoTool(todoArguments)
        ));
        ToolChainService service = new ToolChainService(registry, List.of(chain("news_chain", List.of(
            new ToolChain.Step("get_hot_news", "{\"max_results\": 2}", "numbered_items"),
            new ToolChain.Step("search_news", "{\"query\": \"{{prev.first}}\", \"max_results\": 3}"),
            new ToolChain.Step("add_todo", "{\"action\": \"add\", \"text\": \"{{prev.result}}\"}")
        ))));

        String result = service.run("u1", "news_chain", objectMapper.nullNode());

        assertEquals("AI 头条", searchArguments.get().path("query").asText());
        assertEquals("AI 头条详情摘要", todoArguments.get().path("text").asText());
        assertTrue(result.contains("共 3 步"));
    }

    @Test
    void chainStopsWhenStepFails() throws Exception {
        AtomicBoolean lastToolCalled = new AtomicBoolean(false);
        ToolRegistry registry = new ToolRegistry(List.of(
            echoTool("step_one", "第一步结果"),
            failingTool("step_two"),
            notRunTool("step_three", lastToolCalled)
        ));
        ToolChainService service = new ToolChainService(registry, List.of(chain("fail_chain", List.of(
            new ToolChain.Step("step_one", "{}"),
            new ToolChain.Step("step_two", "{}"),
            new ToolChain.Step("step_three", "{}")
        ))));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.run("u1", "fail_chain", objectMapper.nullNode()));

        assertTrue(ex.getMessage().contains("第 2 步"));
        assertFalse(lastToolCalled.get());
    }

    @Test
    void unresolvedPlaceholderFailsWithStepContext() throws Exception {
        ToolRegistry registry = new ToolRegistry(List.of(echoTool("step_one", "只有文本")));
        ToolChainService service = new ToolChainService(registry, List.of(chain("bad_chain", List.of(
            new ToolChain.Step("step_one", "{}"),
            new ToolChain.Step("step_two", "{\"text\": \"{{prev.title}}\"}")
        ))));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> service.run("u1", "bad_chain", objectMapper.nullNode()));

        assertTrue(ex.getMessage().contains("无法解析占位符"));
        assertTrue(ex.getMessage().contains("第 2 步"));
    }

    @Test
    void chainKeepsUserDataIsolatedWithRealTodoService() throws Exception {
        String url = "jdbc:h2:mem:chain-todo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        TodoService todoService =
            new TodoService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        ToolRegistry registry = new ToolRegistry(List.of(
            locationEchoTool("query_weather"),
            todoTool(todoService)
        ));
        ToolChainService service = new ToolChainService(registry, List.of(chain("weather_todo", List.of(
            new ToolChain.Step("query_weather", "{\"location\": \"{{input.location}}\"}"),
            new ToolChain.Step("manage_todo", "{\"action\": \"add\", \"text\": \"{{prev.result}}\"}")
        ))));

        service.run("u1", "weather_todo", objectMapper.readTree("{\"location\":\"北京\"}"));
        service.run("u2", "weather_todo", objectMapper.readTree("{\"location\":\"上海\"}"));

        assertTrue(todoService.list("u1").contains("北京"));
        assertFalse(todoService.list("u1").contains("上海"));
        assertTrue(todoService.list("u2").contains("上海"));
    }

    @Test
    void registryRegistersAndRejectsDuplicateTool() {
        ToolRegistry registry = new ToolRegistry(List.of());
        registry.register(echoTool("chain_tool", "ok"));

        assertThrows(IllegalStateException.class,
            () -> registry.register(echoTool("chain_tool", "again")));
        assertEquals("ok", registry.executeStrict("u1", "chain_tool", "{}"));
        assertThrows(IllegalArgumentException.class,
            () -> registry.executeStrict("u1", "missing_tool", "{}"));
    }

    private ToolChain chain(String id, List<ToolChain.Step> steps) {
        return new ToolChain(id, "测试链", Map.of(), steps);
    }

    private BotTool echoTool(String name, String reply) {
        return new BotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "回显工具";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return reply;
            }
        };
    }

    private BotTool locationEchoTool(String name) {
        return new BotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "按入参返回城市天气";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                return arguments.path("location").asText("未知城市") + "天气晴";
            }
        };
    }

    private BotTool recordingSearchTool(AtomicReference<JsonNode> argumentsRef) {
        return new BotTool() {
            @Override
            public String name() {
                return "search_news";
            }

            @Override
            public String description() {
                return "搜索新闻详情";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                argumentsRef.set(arguments);
                return "AI 头条详情摘要";
            }
        };
    }

    private BotTool recordingTodoTool(AtomicReference<JsonNode> argumentsRef) {
        return new BotTool() {
            @Override
            public String name() {
                return "add_todo";
            }

            @Override
            public String description() {
                return "添加待办";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                argumentsRef.set(arguments);
                return "已添加待办 #1";
            }
        };
    }

    private BotTool todoTool(TodoService todoService) {
        return new BotTool() {
            @Override
            public String name() {
                return "manage_todo";
            }

            @Override
            public String description() {
                return "管理待办";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                String action = arguments.path("action").asText("");
                if ("add".equals(action)) {
                    return todoService.add(userId, arguments.path("text").asText(""));
                }
                return "不支持的操作: " + action;
            }
        };
    }

    private BotTool failingTool(String name) {
        return new BotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "必然失败";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                throw new IllegalStateException("模拟失败");
            }
        };
    }

    private BotTool notRunTool(String name, AtomicBoolean called) {
        return new BotTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return "不应被调用";
            }

            @Override
            public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of(), "required", List.of());
            }

            @Override
            public String execute(String userId, JsonNode arguments) {
                called.set(true);
                return "不应执行";
            }
        };
    }
}
