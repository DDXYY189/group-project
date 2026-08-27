package com.example.group_demo.agent;

import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.llm.LlmProperties;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.rag.RagProperties;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanAgentServiceTest {

    private HttpServer server;
    private final List<String> responses = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private int callIndex;

    @BeforeEach
    void startServer() throws IOException {
        callIndex = 0;
        responses.clear();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat/completions", this::handleChat);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        String content = callIndex < responses.size() ? responses.get(callIndex) : "";
        callIndex++;
        String body = """
            {"choices":[{"message":{"content":%s}}]}
            """.formatted(toJson(content));
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private String toJson(String text) {
        try {
            return objectMapper.writeValueAsString(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private LlmService newLlm() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("test-model");
        String url = "jdbc:h2:mem:plan-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ConversationMemoryService memory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        return new LlmService(properties, memory);
    }

    private KeywordRagService ragWithWeeklyPlan(boolean enabled) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(enabled);
        return new KeywordRagService(properties, List.of(
            new KnowledgeChunk("weekly-1", "下周课表",
                "大二下周课表：周一高等数学和大学英语，周三全天无课，周五下午无课。"),
            new KnowledgeChunk("weekly-2", "作业与考试",
                "下周作业与考试安排：高等数学作业周五前提交，大学英语作文周三前提交，"
                    + "数据结构下下周三小测。")
        ));
    }

    @Test
    void runsDecomposeExecuteIntegrateLoop() {
        responses.addAll(List.of(
            "1. 查询下周课表\n2. 查询作业和考试安排\n3. 了解个人运动与作息偏好",
            "周一有高等数学和大学英语，周三全天无课",
            "高等数学作业周五前提交，数据结构下下周三小测",
            "用户偏好晚饭后运动，晚上 21:00 后复习",
            "完整周计划：周一至周五按课表上课，晚上复习，周末运动和休息。"
        ));
        PlanAgentService agent = new PlanAgentService(newLlm(), ragWithWeeklyPlan(true));

        String reply = agent.run("u1", "帮我制定下周兼顾课程、运动、社团和复习的安排");

        assertTrue(reply.contains("完整周计划"));
        assertTrue(reply.contains("Agent 执行过程"));
        assertTrue(reply.contains("查询下周课表 ✓"));
        assertTrue(reply.contains("查询作业和考试安排 ✓"));
        assertEquals(5, callIndex);
    }

    @Test
    void fallsBackToLlmWhenRagIsDisabled() {
        responses.addAll(List.of(
            "1. 查询课表\n2. 查询偏好",
            "课表回答",
            "偏好回答",
            "整合后的周计划"
        ));
        PlanAgentService agent = new PlanAgentService(newLlm(), ragWithWeeklyPlan(false));

        String reply = agent.run("u1", "帮我制定学习计划");

        assertTrue(reply.contains("整合后的周计划"));
        assertEquals(4, callIndex);
    }

    @Test
    void returnsHintWhenGoalCannotBeDecomposed() {
        responses.add("");
        PlanAgentService agent = new PlanAgentService(newLlm(), ragWithWeeklyPlan(true));

        String reply = agent.run("u1", "随便");

        assertTrue(reply.contains("太模糊"));
        assertEquals(1, callIndex);
    }
}
