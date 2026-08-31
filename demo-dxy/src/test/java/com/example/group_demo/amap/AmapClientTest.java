package com.example.group_demo.amap;

import com.example.group_demo.amap.AmapClient.AmapPoint;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URLDecoder;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AmapClientTest {

    private HttpServer server;
    private final AtomicReference<String> staticMapQuery = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        staticMapQuery.set(null);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.contains("/v3/place/text")) {
            body = """
                {"status":"1","pois":[{"name":"外滩","location":"121.492127,31.233516"}]}
                """;
        } else if (path.contains("/v3/geocode/geo")) {
            body = "{\"status\":\"1\",\"geocodes\":[]}";
        } else if (path.contains("/v3/direction/walking")) {
            body = """
                {"status":"1","route":{"paths":[{"steps":[
                  {"polyline":"121.473889,31.230195;121.474067,31.230321"}
                ]}]}}
                """;
        } else if (path.contains("/v3/staticmap")) {
            staticMapQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] png = new byte[]{1, 2, 3};
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, png.length);
            exchange.getResponseBody().write(png);
            exchange.close();
            return;
        } else {
            body = "{\"status\":\"0\",\"info\":\"not found\"}";
        }
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private AmapClient newClient() {
        AmapProperties properties = new AmapProperties();
        properties.setEnabled(true);
        properties.setRestKey("test-key");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        return new AmapClient(properties);
    }

    @Test
    void locatesScenicSpotCoordinates() {
        AmapPoint point = newClient().locate("外滩", "上海");

        assertEquals("外滩", point.name());
        assertEquals(121.492127, point.lng());
        assertEquals(31.233516, point.lat());
    }

    @Test
    void joinsWalkingRouteStepsIntoPolyline() {
        String polyline = newClient().routePolyline("121.473889,31.230195", "121.474067,31.230321");

        assertTrue(polyline.contains("121.473889,31.230195"));
        assertTrue(polyline.contains("121.474067,31.230321"));
    }

    @Test
    void buildsStaticMapWithMarkersAndPath() {
        byte[] png = newClient().staticMapImage(List.of(
            new AmapPoint("外滩", 1, 121.492127, 31.233516),
            new AmapPoint("城隍庙", 1, 121.487747, 31.227173)));

        assertArrayEquals(new byte[]{1, 2, 3}, png);
        String query = staticMapQuery.get();
        assertTrue(query.contains("key=test-key"));
        assertTrue(query.contains("markers="));
        assertTrue(query.contains("paths="));
        assertTrue(query.contains("location="));
        assertTrue(URLDecoder.decode(query, StandardCharsets.UTF_8)
            .contains("paths=5,0x0F766E,0.8,,:"));
    }

    @Test
    void failsWhenRestKeyMissing() {
        AmapProperties properties = new AmapProperties();
        properties.setEnabled(true);
        properties.setRestKey("");
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());

        assertThrows(IllegalStateException.class, () -> new AmapClient(properties).locate("外滩", "上海"));
    }
}
