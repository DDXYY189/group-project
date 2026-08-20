package com.example.group_demo.tool;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SentimentAnalysisTool 单元测试。
 * 使用内嵌 HttpServer 模拟 DashScope 兼容模式接口，参考 WebSearchToolTest 写法。
 */
class SentimentAnalysisToolTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /** 模拟 DashScope chat/completions 返回标准情感分析 JSON */
    private void handleChat(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String json = """
            {"choices":[{"message":{"role":"assistant","content":"{\\"overall\\":\\"消极\\",\\"subEmotion\\":\\"抱怨\\",\\"confidence\\":92,\\"keywords\\":[\\"服务太差\\",\\"等了半个小时\\"],\\"reason\\":\\"用户对等待时间和服务质量表达不满\\"}"}}]}
            """;
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private SentimentAnalysisTool newTool() {
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        return new SentimentAnalysisTool("test-key", "qwen-plus", baseUrl);
    }

    @Test
    void analyzesAndFormatsResult() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"text\":\"这家店的服务太差了，等了半个小时都没人理！\"}");

        String result = newTool().execute("u1", arguments);

        assertTrue(result.contains("整体情感: 消极"));
        assertTrue(result.contains("情绪细分: 抱怨"));
        assertTrue(result.contains("置信度: 92%"));
        assertTrue(result.contains("关键词: 服务太差、等了半个小时"));
        assertTrue(result.contains("理由: 用户对等待时间和服务质量表达不满"));
        assertEquals("Bearer test-key", authHeader.get());

        JsonNode requestBody = objectMapper.readTree(requests.get(0));
        assertEquals("qwen-plus", requestBody.path("model").asText());
        assertEquals("这家店的服务太差了，等了半个小时都没人理！",
            requestBody.path("messages").get(1).path("content").asText());
    }

    @Test
    void rejectsEmptyText() throws Exception {
        SentimentAnalysisTool tool = newTool();
        JsonNode arguments = objectMapper.readTree("{\"text\":\"  \"}");

        String result = tool.execute("u1", arguments);

        assertEquals("待分析文本为空，无法进行情感分析", result);
    }

    @Test
    void missingKeyReturnsClearMessage() throws Exception {
        SentimentAnalysisTool tool =
            new SentimentAnalysisTool("", "qwen-plus", "http://127.0.0.1:" + server.getAddress().getPort());
        JsonNode arguments = objectMapper.readTree("{\"text\":\"测试文本\"}");

        String result = tool.execute("u1", arguments);

        assertTrue(result.contains("API Key 未配置"));
    }

    @Test
    void handlesMarkdownWrappedJson() throws Exception {
        server.removeContext("/chat/completions");
        server.createContext("/chat/completions", exchange -> {
            String json = """
                {"choices":[{"message":{"role":"assistant","content":"```json\\n{\\"overall\\":\\"积极\\",\\"subEmotion\\":\\"夸赞\\",\\"confidence\\":88,\\"keywords\\":[\\"太棒了\\"],\\"reason\\":\\"表达了强烈赞美\\"}\\n```"}}]}
                """;
            byte[] response = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        JsonNode arguments = objectMapper.readTree("{\"text\":\"太棒了！\"}");

        String result = newTool().execute("u1", arguments);

        assertTrue(result.contains("整体情感: 积极"));
        assertTrue(result.contains("情绪细分: 夸赞"));
    }
}
