package com.example.group_demo.llm;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmServiceMemoryTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger callCount = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
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
        String content;
        if (body.contains("对话摘要助手")) {
            content = "摘要：用户叫小明。";
        } else {
            int index = callCount.getAndIncrement();
            content = index == 0 ? "你好，小明" : "你刚才说你叫小明。";
        }
        String json = """
            {"choices":[{"message":{"content":"%s"}}]}
            """.formatted(content);
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private LlmProperties buildProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setModel("qwen-plus");
        return properties;
    }

    private ConversationMemoryService newConversationMemory(LlmProperties properties) {
        String url = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        return new ConversationMemoryService(new JdbcTemplate(dataSource), properties);
    }

    private LlmService buildLlmService() {
        LlmProperties properties = buildProperties();
        return new LlmService(properties, newConversationMemory(properties));
    }

    @Test
    void sendsHistoryOnSecondTurn() throws Exception {
        LlmService llm = buildLlmService();

        assertEquals("你好，小明", llm.chat("u1", "我叫小明"));
        assertEquals("你刚才说你叫小明。", llm.chat("u1", "我叫什么"));

        assertEquals(2, requests.size());
        JsonNode firstMessages = objectMapper.readTree(requests.get(0)).path("messages");
        assertEquals(2, firstMessages.size());
        assertEquals("system", firstMessages.get(0).path("role").asText());
        assertEquals("我叫小明", firstMessages.get(1).path("content").asText());

        JsonNode secondMessages = objectMapper.readTree(requests.get(1)).path("messages");
        assertEquals(4, secondMessages.size());
        assertEquals("我叫小明", secondMessages.get(1).path("content").asText());
        assertEquals("assistant", secondMessages.get(2).path("role").asText());
        assertEquals("你好，小明", secondMessages.get(2).path("content").asText());
        assertEquals("我叫什么", secondMessages.get(3).path("content").asText());
    }

    @Test
    void usersAreIsolated() throws Exception {
        LlmService llm = buildLlmService();

        llm.chat("u1", "我叫小明");
        llm.chat("u2", "我叫小红");

        assertEquals(2, requests.size());
        JsonNode firstMessages = objectMapper.readTree(requests.get(0)).path("messages");
        JsonNode secondMessages = objectMapper.readTree(requests.get(1)).path("messages");
        assertEquals(2, firstMessages.size());
        assertEquals(2, secondMessages.size());
    }

    @Test
    void survivesRestartOnSameDatabase() throws Exception {
        String url = "jdbc:h2:mem:restart-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

        LlmProperties firstProperties = buildProperties();
        ConversationMemoryService firstMemory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), firstProperties);
        LlmService first = new LlmService(firstProperties, firstMemory);
        first.chat("u1", "我叫小明");

        LlmProperties secondProperties = buildProperties();
        ConversationMemoryService secondMemory = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), secondProperties);
        LlmService second = new LlmService(secondProperties, secondMemory);
        second.chat("u1", "我叫什么");

        assertEquals(2, requests.size());
        JsonNode secondMessages = objectMapper.readTree(requests.get(1)).path("messages");
        assertEquals(4, secondMessages.size());
        assertEquals("我叫小明", secondMessages.get(1).path("content").asText());
        assertEquals("你好，小明", secondMessages.get(2).path("content").asText());
    }

    @Test
    void compactsOldTurnsIntoSummary() throws Exception {
        LlmProperties properties = buildProperties();
        properties.getMemory().setMaxTurns(5);
        properties.getMemory().setSummaryEnabled(true);
        properties.getMemory().setSummaryThresholdTurns(1);
        properties.getMemory().setRecentTurns(1);
        ConversationMemoryService memory = newConversationMemory(properties);
        LlmService llm = new LlmService(properties, memory);

        llm.chat("u1", "第一轮");
        llm.chat("u1", "第二轮");
        llm.chat("u1", "第三轮");

        assertEquals(4, requests.size());
        assertTrue(requests.get(2).contains("对话摘要助手"));

        JsonNode lastMessages = objectMapper.readTree(requests.get(3)).path("messages");
        assertEquals(5, lastMessages.size());
        assertEquals("system", lastMessages.get(0).path("role").asText());
        assertEquals("system", lastMessages.get(1).path("role").asText());
        assertTrue(lastMessages.get(1).path("content").asText().contains("摘要"));

        ConversationMemoryService.ChatContext context = memory.load("u1");
        assertEquals("摘要：用户叫小明。", context.summary());
        assertEquals(4, context.turns().size());
    }
}
