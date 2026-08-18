package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * 天气服务：接入和风天气（QWeather）API，获取实时天气数据。
 *
 * 认证方式：X-QW-Api-Key 请求头（QWeather 新版认证）
 * API 主机：每个项目独有的 {custom_id}.qweatherapi.com
 * 城市查询：内置常用城市 Location ID 查找表（geoapi 在新主机上不可用）
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final LlmProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final Map<String, CacheEntry> cache = new HashMap<>();

    private static final Map<String, String> CITY_IDS = new HashMap<>();

    static {
        CITY_IDS.put("北京", "101010100");
        CITY_IDS.put("beijing", "101010100");
        CITY_IDS.put("上海", "101020100");
        CITY_IDS.put("shanghai", "101020100");
        CITY_IDS.put("广州", "101280101");
        CITY_IDS.put("guangzhou", "101280101");
        CITY_IDS.put("深圳", "101280601");
        CITY_IDS.put("shenzhen", "101280601");
        CITY_IDS.put("杭州", "101210101");
        CITY_IDS.put("hangzhou", "101210101");
        CITY_IDS.put("南京", "101190101");
        CITY_IDS.put("nanjing", "101190101");
        CITY_IDS.put("成都", "101270101");
        CITY_IDS.put("chengdu", "101270101");
        CITY_IDS.put("重庆", "101040100");
        CITY_IDS.put("chongqing", "101040100");
        CITY_IDS.put("武汉", "101200101");
        CITY_IDS.put("wuhan", "101200101");
        CITY_IDS.put("西安", "101110101");
        CITY_IDS.put("xian", "101110101");
        CITY_IDS.put("天津", "101030100");
        CITY_IDS.put("tianjin", "101030100");
        CITY_IDS.put("苏州", "101190401");
        CITY_IDS.put("suzhou", "101190401");
        CITY_IDS.put("长沙", "101250101");
        CITY_IDS.put("changsha", "101250101");
        CITY_IDS.put("郑州", "101180101");
        CITY_IDS.put("zhengzhou", "101180101");
        CITY_IDS.put("青岛", "101120201");
        CITY_IDS.put("qingdao", "101120201");
        CITY_IDS.put("沈阳", "101070101");
        CITY_IDS.put("shenyang", "101070101");
        CITY_IDS.put("哈尔滨", "101050101");
        CITY_IDS.put("harbin", "101050101");
        CITY_IDS.put("大连", "101070201");
        CITY_IDS.put("dalian", "101070201");
        CITY_IDS.put("昆明", "101290101");
        CITY_IDS.put("kunming", "101290101");
        CITY_IDS.put("厦门", "101230201");
        CITY_IDS.put("xiamen", "101230201");
        CITY_IDS.put("合肥", "101220101");
        CITY_IDS.put("hefei", "101220101");
        CITY_IDS.put("福州", "101230101");
        CITY_IDS.put("fuzhou", "101230101");
        CITY_IDS.put("南昌", "101240101");
        CITY_IDS.put("nanchang", "101240101");
        CITY_IDS.put("济南", "101120101");
        CITY_IDS.put("jinan", "101120101");
        CITY_IDS.put("石家庄", "101090101");
        CITY_IDS.put("shijiazhuang", "101090101");
        CITY_IDS.put("太原", "101100101");
        CITY_IDS.put("taiyuan", "101100101");
        CITY_IDS.put("长春", "101060101");
        CITY_IDS.put("changchun", "101060101");
        CITY_IDS.put("贵阳", "101260101");
        CITY_IDS.put("guiyang", "101260101");
        CITY_IDS.put("南宁", "101300101");
        CITY_IDS.put("nanning", "101300101");
        CITY_IDS.put("兰州", "101160101");
        CITY_IDS.put("lanzhou", "101160101");
        CITY_IDS.put("银川", "101170101");
        CITY_IDS.put("yinchuan", "101170101");
        CITY_IDS.put("西宁", "101150101");
        CITY_IDS.put("xining", "101150101");
        CITY_IDS.put("海口", "101310101");
        CITY_IDS.put("haikou", "101310101");
        CITY_IDS.put("呼和浩特", "101080101");
        CITY_IDS.put("hohhot", "101080101");
        CITY_IDS.put("乌鲁木齐", "101130101");
        CITY_IDS.put("urumqi", "101130101");
        CITY_IDS.put("拉萨", "101140101");
        CITY_IDS.put("lhasa", "101140101");
        CITY_IDS.put("宁波", "101210401");
        CITY_IDS.put("ningbo", "101210401");
        CITY_IDS.put("无锡", "101190201");
        CITY_IDS.put("wuxi", "101190201");
        CITY_IDS.put("佛山", "101280800");
        CITY_IDS.put("foshan", "101280800");
        CITY_IDS.put("东莞", "101281601");
        CITY_IDS.put("dongguan", "101281601");
        CITY_IDS.put("珠海", "101280701");
        CITY_IDS.put("zhuhai", "101280701");
        CITY_IDS.put("三亚", "101310201");
        CITY_IDS.put("sanya", "101310201");
        CITY_IDS.put("温州", "101210701");
        CITY_IDS.put("wenzhou", "101210701");
    }

    public WeatherService(LlmProperties props) {
        this.props = props;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getWeather(String city) {
        if (city == null || city.isBlank()) {
            return null;
        }
        if (props.getWeather().getApiKey() == null || props.getWeather().getApiKey().isBlank()) {
            log.warn("天气 API Key 未配置");
            return "天气服务未配置，请联系管理员设置和风天气 API Key。";
        }

        String normalizedCity = city.trim();
        String host = props.getWeather().getHost();

        CacheEntry cached = cache.get(normalizedCity);
        if (cached != null && cached.expiresAt.isAfter(LocalDateTime.now())) {
            log.info("天气缓存命中: {}", normalizedCity);
            return cached.description;
        }

        String locationId = lookupCityId(normalizedCity);
        if (locationId == null) {
            return "暂不支持查询「" + normalizedCity + "」的天气，请尝试输入主要城市名（如北京、上海、广州等）。";
        }

        try {
            String weatherJson = fetchWeather(locationId, host);
            String description = parseWeather(weatherJson, normalizedCity);

            int cacheMin = props.getWeather().getCacheMinutes();
            cache.put(normalizedCity, new CacheEntry(description,
                    LocalDateTime.now().plusMinutes(cacheMin)));

            return description;
        } catch (Exception e) {
            log.error("天气查询失败 city={}: {}", normalizedCity, e.getMessage());
            return "天气查询失败: " + e.getMessage();
        }
    }

    private String lookupCityId(String city) {
        String key = city.toLowerCase();
        if (CITY_IDS.containsKey(key)) {
            return CITY_IDS.get(key);
        }
        for (Map.Entry<String, String> e : CITY_IDS.entrySet()) {
            if (e.getKey().contains(key) || key.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private String fetchWeather(String locationId, String host) throws Exception {
        String url = String.format("https://%s/v7/weather/now?location=%s", host, locationId);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-QW-Api-Key", props.getWeather().getApiKey())
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        byte[] body = resp.body();
        String text;
        if (body.length >= 2 && (body[0] & 0xFF) == 0x1F && (body[1] & 0xFF) == 0x8B) {
            try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(body))) {
                text = new String(gz.readAllBytes(), StandardCharsets.UTF_8);
            }
        } else {
            text = new String(body, StandardCharsets.UTF_8);
        }
        log.info("天气API响应 code={} bodyLength={} gzip={}", resp.statusCode(), text.length(),
                body.length >= 2 && (body[0] & 0xFF) == 0x1F);
        return text;
    }

    private String parseWeather(String json, String city) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String code = root.path("code").asText("");
        if (!"200".equals(code)) {
            log.warn("天气数据返回 code={}: {}", code, json);
            return "天气数据获取失败（code=" + code + "）";
        }
        JsonNode now = root.path("now");
        String temp = now.path("temp").asText("?") + "°C";
        String feelsLike = now.path("feelsLike").asText("?") + "°C";
        String text = now.path("text").asText("未知");
        String windDir = now.path("windDir").asText("未知");
        String windScale = now.path("windScale").asText("?");
        String humidity = now.path("humidity").asText("?") + "%";
        String precip = now.path("precip").asText("0");

        return String.format(
                "%s当前天气：%s，气温%s（体感%s），%s风%s级，湿度%s，降水量%smm",
                city, text, temp, feelsLike, windDir, windScale, humidity, precip
        );
    }

    private record CacheEntry(String description, LocalDateTime expiresAt) {}
}
