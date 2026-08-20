package com.example.group_demo.news;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NewsServiceTest {

    private HttpServer server;
    private final AtomicReference<String> lastPath = new AtomicReference<>();
    private final AtomicReference<String> payload = new AtomicReference<>(defaultPayload());

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v2/60s", this::handleHotList);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static String defaultPayload() {
        return """
            {"code":200,"message":"ok","data":{"date":"2026-08-19","news":[
              "第一条新闻",
              "第二条新闻",
              "第三条新闻"
            ]}}
            """;
    }

    private void handleHotList(HttpExchange exchange) throws IOException {
        lastPath.set(exchange.getRequestURI().getPath());
        byte[] response = payload.get().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private NewsService newService() {
        NewsProperties properties = new NewsProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v2/60s");
        return new NewsService(properties);
    }

    @Test
    void fetchesDailyNews() {
        String result = newService().getHotNews(2);

        assertTrue(result.contains("每日热点（2026-08-19）Top 2"));
        assertTrue(result.contains("第一条新闻"));
        assertFalse(result.contains("第三条新闻"));
        assertEquals("/v2/60s", lastPath.get());
    }

    @Test
    void capsResultCount() {
        String result = newService().getHotNews(100);

        assertTrue(result.contains("Top 3"));
    }

    @Test
    void rejectsEmptyNews() {
        payload.set("{\"code\":200,\"data\":{\"date\":\"2026-08-19\",\"news\":[]}}");
        assertThrows(IllegalStateException.class, () -> newService().getHotNews(5));
    }

    @Test
    void rejectsNonSuccessCode() {
        payload.set("{\"code\":500,\"message\":\"error\",\"data\":null}");
        assertThrows(IllegalStateException.class, () -> newService().getHotNews(5));
    }
}
