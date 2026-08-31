package com.example.group_demo.amap;

import com.example.group_demo.config.RestClientFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 高德 Web 服务客户端：把景点名称转成经纬度，计算每日步行路线，
 * 最后生成一张带标记和路线折线的静态地图图片。
 */
@Service
public class AmapClient {

    private static final Logger log = LoggerFactory.getLogger(AmapClient.class);
    private static final int MAX_PATH_POINTS = 50;
    private static final String[] DAY_COLORS = {
        "0x0F766E", "0xE05D44", "0x2E6BE6", "0x9C27B0", "0xF59E0B"
    };
    private static final int MAX_RETRIES = 3;
    private static final long MIN_REQUEST_INTERVAL_MS = 300;
    private static final long QPS_BACKOFF_MS = 400;
    private static final String QPS_LIMITED_INFOCODE = "10021";

    private final AmapProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestClient restClient;
    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();
    private long lastRequestTime;

    public AmapClient(AmapProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
    }

    public AmapPoint locate(String name, String city) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("景点名称不能为空");
        }
        String cacheKey = "locate|" + orBlank(city) + "|" + name;
        CachedEntry cached = get(cacheKey);
        if (cached != null) {
            return (AmapPoint) cached.value();
        }
        String location = firstLocation(
            getJson("/v3/place/text", Map.of("keywords", name, "city", orBlank(city), "offset", "1")),
            "pois");
        if (location == null) {
            location = firstLocation(
                getJson("/v3/geocode/geo", Map.of("address", orBlank(city) + name)),
                "geocodes");
        }
        if (location == null) {
            throw new IllegalStateException("高德未找到景点坐标: " + name);
        }
        String[] parts = location.split(",");
        AmapPoint point = new AmapPoint(name, 0,
            Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim()));
        put(cacheKey, point);
        return point;
    }

    public String routePolyline(String origin, String destination) {
        String cacheKey = "route|" + origin + "|" + destination;
        CachedEntry cached = get(cacheKey);
        if (cached != null) {
            return (String) cached.value();
        }
        String mode = orBlank(properties.getRouteMode()).isBlank()
            ? "walking" : properties.getRouteMode();
        JsonNode root = getJson("/v3/direction/" + mode,
            Map.of("origin", origin, "destination", destination));
        JsonNode paths = root.path("route").path("paths");
        if (!paths.isArray() || paths.isEmpty()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        JsonNode steps = paths.get(0).path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                String polyline = step.path("polyline").asText("");
                if (!polyline.isBlank()) {
                    segments.add(polyline);
                }
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        String result = segments.stream().collect(Collectors.joining(";"));
        put(cacheKey, result);
        return result;
    }

    public byte[] staticMapImage(List<AmapPoint> points) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        requireConfigured();

        Map<String, String> single = new LinkedHashMap<>();
        single.put("key", properties.getRestKey());
        single.put("location", center(points));
        single.put("zoom", String.valueOf(zoom(points)));
        single.put("size", "750*400");

        Map<String, List<String>> repeated = new LinkedHashMap<>();
        List<String> markers = new ArrayList<>();
        char label = 'A';
        for (AmapPoint point : points) {
            markers.add("mid,0xE05D44," + label++ + ":" + point.lng() + "," + point.lat());
        }
        repeated.put("markers", markers);

        Map<Integer, List<AmapPoint>> byDay = new TreeMap<>();
        for (AmapPoint point : points) {
            byDay.computeIfAbsent(point.day(), key -> new ArrayList<>()).add(point);
        }
        List<String> paths = new ArrayList<>();
        int dayIndex = 0;
        for (Map.Entry<Integer, List<AmapPoint>> entry : byDay.entrySet()) {
            String color = DAY_COLORS[dayIndex % DAY_COLORS.length];
            paths.add("5," + color + ",0.8,,:" + polylineForDay(entry.getValue()));
            dayIndex++;
        }
        repeated.put("paths", paths);

        String url = properties.getBaseUrl() + "/v3/staticmap?" + buildQuery(single, repeated);
        return restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
    }

    /**
     * 构建前端高德 JS API 可交互地图所需的结构化数据：
     * 景点标记（含经纬度与所属天）+ 每天步行路线折线坐标（按天分色）。
     * 路线复用 {@link #routePolyline} 的缓存，单段失败时回退为两端点直线。
     */
    public MapData interactiveMapData(List<AmapPoint> points) {
        if (points == null || points.isEmpty()) {
            return new MapData(List.of(), List.of());
        }
        List<MapPoint> mapPoints = new ArrayList<>();
        for (AmapPoint point : points) {
            mapPoints.add(new MapPoint(point.name(), point.day(), point.lng(), point.lat()));
        }

        Map<Integer, List<AmapPoint>> byDay = new TreeMap<>();
        for (AmapPoint point : points) {
            byDay.computeIfAbsent(point.day(), key -> new ArrayList<>()).add(point);
        }
        List<MapPath> paths = new ArrayList<>();
        int dayIndex = 0;
        for (Map.Entry<Integer, List<AmapPoint>> entry : byDay.entrySet()) {
            String color = "#" + DAY_COLORS[dayIndex % DAY_COLORS.length].substring(2);
            List<double[]> coords = polylineCoords(entry.getValue());
            if (!coords.isEmpty()) {
                paths.add(new MapPath(entry.getKey(), color, coords));
            }
            dayIndex++;
        }
        return new MapData(mapPoints, paths);
    }

    public boolean isInteractiveMapEnabled() {
        return properties.isJsEnabled();
    }

    private String polylineForDay(List<AmapPoint> dayPoints) {
        List<double[]> coords = polylineCoords(dayPoints);
        List<String> parts = new ArrayList<>(coords.size());
        for (double[] coord : coords) {
            parts.add(coord[0] + "," + coord[1]);
        }
        return String.join(";", parts);
    }

    private List<double[]> polylineCoords(List<AmapPoint> dayPoints) {
        if (dayPoints.isEmpty()) {
            return List.of();
        }
        if (dayPoints.size() == 1) {
            AmapPoint point = dayPoints.get(0);
            return new ArrayList<>(List.of(new double[]{point.lng(), point.lat()}));
        }
        List<double[]> coords = new ArrayList<>();
        for (int i = 0; i < dayPoints.size() - 1; i++) {
            AmapPoint from = dayPoints.get(i);
            AmapPoint to = dayPoints.get(i + 1);
            List<double[]> segment = segmentCoords(from, to);
            if (segment.isEmpty()) {
                coords.add(new double[]{from.lng(), from.lat()});
                coords.add(new double[]{to.lng(), to.lat()});
            } else {
                coords.addAll(segment);
            }
        }
        return decimateCoords(coords, MAX_PATH_POINTS);
    }

    private List<double[]> segmentCoords(AmapPoint from, AmapPoint to) {
        String polyline;
        try {
            polyline = routePolyline(
                from.lng() + "," + from.lat(), to.lng() + "," + to.lat());
        } catch (Exception e) {
            log.warn("高德路线规划失败 {} -> {}", from.name(), to.name(), e);
            return List.of();
        }
        if (polyline == null || polyline.isBlank()) {
            return List.of();
        }
        List<double[]> coords = new ArrayList<>();
        for (String coord : polyline.split(";")) {
            String[] pair = coord.trim().split(",");
            if (pair.length == 2) {
                try {
                    coords.add(new double[]{
                        Double.parseDouble(pair[0].trim()),
                        Double.parseDouble(pair[1].trim())
                    });
                } catch (NumberFormatException ignored) {
                    // 跳过格式异常的坐标点
                }
            }
        }
        return coords;
    }

    private static List<double[]> decimateCoords(List<double[]> coords, int max) {
        if (coords.size() <= max) {
            return coords;
        }
        List<double[]> result = new ArrayList<>(max);
        for (int i = 0; i < max; i++) {
            int index = (int) Math.round(i * (coords.size() - 1) / (double) (max - 1));
            result.add(coords.get(index));
        }
        return result;
    }

    private JsonNode getJson(String path, Map<String, String> params) {
        requireConfigured();
        Map<String, String> all = new LinkedHashMap<>();
        all.put("key", properties.getRestKey());
        all.put("output", "json");
        all.putAll(params);
        String query = all.entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        String url = properties.getBaseUrl() + path + "?" + query;

        for (int attempt = 0; ; attempt++) {
            throttle();
            String body = restClient.get().uri(URI.create(url)).retrieve().body(String.class);
            JsonNode root;
            try {
                root = objectMapper.readTree(body);
            } catch (Exception e) {
                throw new IllegalStateException("高德响应解析失败", e);
            }
            if ("1".equals(root.path("status").asText())) {
                return root;
            }
            String infocode = root.path("infocode").asText();
            if (QPS_LIMITED_INFOCODE.equals(infocode) && attempt < MAX_RETRIES) {
                long backoff = QPS_BACKOFF_MS << attempt;
                log.warn("高德 QPS 限流({}), 第 {} 次重试, 等待 {}ms", infocode, attempt + 1, backoff);
                sleep(backoff);
                continue;
            }
            throw new IllegalStateException("高德接口错误: "
                + root.path("info").asText() + " (" + infocode + ")");
        }
    }

    private synchronized void throttle() {
        long wait = MIN_REQUEST_INTERVAL_MS - (System.currentTimeMillis() - lastRequestTime);
        if (wait > 0) {
            sleep(wait);
        }
        lastRequestTime = System.currentTimeMillis();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void requireConfigured() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("高德地图未启用");
        }
        if (properties.getRestKey() == null || properties.getRestKey().isBlank()) {
            throw new IllegalStateException("高德 Web 服务 key 未配置");
        }
    }

    private static String firstLocation(JsonNode root, String field) {
        JsonNode items = root.path(field);
        if (items.isArray()) {
            for (JsonNode item : items) {
                String location = item.path("location").asText(null);
                if (location != null && !location.isBlank()) {
                    return location;
                }
            }
        }
        return null;
    }

    private static String center(List<AmapPoint> points) {
        double lng = points.stream().mapToDouble(AmapPoint::lng).average().orElse(0);
        double lat = points.stream().mapToDouble(AmapPoint::lat).average().orElse(0);
        return lng + "," + lat;
    }

    private static int zoom(List<AmapPoint> points) {
        double minLng = points.stream().mapToDouble(AmapPoint::lng).min().orElse(0);
        double maxLng = points.stream().mapToDouble(AmapPoint::lng).max().orElse(0);
        double minLat = points.stream().mapToDouble(AmapPoint::lat).min().orElse(0);
        double maxLat = points.stream().mapToDouble(AmapPoint::lat).max().orElse(0);
        double span = Math.max(maxLng - minLng, maxLat - minLat);
        if (span <= 0.01) {
            return 16;
        }
        if (span <= 0.03) {
            return 15;
        }
        if (span <= 0.08) {
            return 14;
        }
        if (span <= 0.2) {
            return 13;
        }
        if (span <= 0.5) {
            return 12;
        }
        return 10;
    }

    private static String buildQuery(Map<String, String> single,
                                     Map<String, List<String>> repeated) {
        List<String> pairs = new ArrayList<>();
        single.forEach((key, value) -> pairs.add(encode(key) + "=" + encode(value)));
        repeated.forEach((key, values) ->
            values.forEach(value -> pairs.add(encode(key) + "=" + encode(value))));
        return String.join("&", pairs);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private CachedEntry get(String key) {
        CachedEntry entry = cache.get(key);
        if (entry != null && entry.expiresAt() > System.currentTimeMillis()) {
            return entry;
        }
        return null;
    }

    private void put(String key, Object value) {
        cache.put(key, new CachedEntry(value,
            System.currentTimeMillis() + properties.getCacheTtlSeconds() * 1000L));
    }

    private static String orBlank(String text) {
        return text == null ? "" : text;
    }

    public record AmapPoint(String name, int day, double lng, double lat) {
    }

    /**
     * 前端可交互地图数据：景点标记 + 按天分色路线折线。
     * 由 TravelPageRenderer 序列化为 JSON 注入网页，供高德 JS API 渲染。
     */
    public record MapData(List<MapPoint> points, List<MapPath> paths) {
    }

    public record MapPoint(String name, int day, double lng, double lat) {
    }

    public record MapPath(int day, String color, List<double[]> coords) {
    }

    private record CachedEntry(Object value, long expiresAt) {
    }
}
