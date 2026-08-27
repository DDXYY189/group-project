package com.example.group_demo.tool;

import com.example.group_demo.search.SearchProperties;
import com.example.group_demo.search.SearchService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebSearchToolTest {

    private HttpServer server;
    private final List<String> requests = new ArrayList<>();
    private final AtomicReference<String> authHeader = new AtomicReference<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v4/chat/completions", this::handleSearch);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handleSearch(HttpExchange exchange) throws IOException {
        requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        String json = """
            {"choices":[{"message":{"role":"assistant","content":"阿里云百炼是大模型服务平台，参考：https://example.com/1"}}]}
            """;
        byte[] response = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private WebSearchTool newTool() {
        SearchProperties properties = new SearchProperties();
        properties.setApiKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v4/chat/completions");
        return new WebSearchTool(new SearchService(properties), properties);
    }

    @Test
    void searchesAndFormatsResults() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"query\":\"大模型\",\"max_results\":2}");

        String result = newTool().execute("u1", arguments);

        assertTrue(result.contains("阿里云百炼"));
        assertTrue(result.contains("https://example.com/1"));
        assertEquals("Bearer test-key", authHeader.get());

        JsonNode requestBody = objectMapper.readTree(requests.get(0));
        assertEquals("glm-4-flash", requestBody.path("model").asText());
        assertEquals("大模型", requestBody.path("messages").get(1).path("content").asText());
        JsonNode searchTool = requestBody.path("tools").get(0);
        assertEquals("web_search", searchTool.path("type").asText());
        JsonNode webSearch = searchTool.path("web_search");
        assertEquals(true, webSearch.path("enable").asBoolean());
        assertEquals("search_pro", webSearch.path("search_engine").asText());
        assertEquals(true, webSearch.path("search_result").asBoolean());
        assertTrue(webSearch.path("search_prompt").asText().contains("{search_result}"));
        assertEquals(2, webSearch.path("count").asInt());
        assertEquals("auto", requestBody.path("tool_choice").asText());
        assertEquals(false, requestBody.path("stream").asBoolean());
    }

    @Test
    void rejectsBlankQuery() throws Exception {
        JsonNode arguments = objectMapper.readTree("{\"query\":\"  \"}");
        assertThrows(IllegalArgumentException.class, () -> newTool().execute("u1", arguments));
    }

    @Test
    void missingKeyFailsClearly() throws Exception {
        SearchProperties properties = new SearchProperties();
        properties.setApiKey("");
        properties.setBaseUrl("http://127.0.0.1:1/v4/chat/completions");
        WebSearchTool tool = new WebSearchTool(new SearchService(properties), properties);
        JsonNode arguments = objectMapper.readTree("{\"query\":\"测试\"}");

        assertThrows(IllegalStateException.class, () -> tool.execute("u1", arguments));
    }
}
