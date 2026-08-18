package com.youkeda.wechatbotdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * 天气查询服务（心知天气）。
 * API Key 优先从环境变量 SENIVERSE_API_KEY 读取，
 * 读不到再从 classpath 下的 application.properties 里读 seniverse.api-key。
 */
public class WeatherService {

    private final String apiKey;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherService() {
        this.apiKey = loadApiKey();
        System.out.println("天气服务初始化完成，API Key = " + mask(apiKey));
    }

    private String loadApiKey() {
        // 1. 先尝试环境变量
        String key = System.getenv("SENIVERSE_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        // 2. 再尝试 application.properties
        Properties props = new Properties();
        try (InputStream in = WeatherService.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.properties 失败: " + e.getMessage(), e);
        }
        return props.getProperty("seniverse.api-key", "").trim();
    }

    /**
     * 查询指定城市的实时天气。
     *
     * @param location 城市名，如 "杭州"、"北京"
     * @return 天气描述文本
     */
    public String queryWeather(String location) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            return "天气服务未配置：请在环境变量 SENIVERSE_API_KEY 或 application.properties 的 seniverse.api-key 填入心知天气公钥";
        }

        // 1. 拼 URL，调用心知天气实时天气接口
        String urlStr = "https://api.seniverse.com/v3/weather/now.json"
                + "?key=" + apiKey
                + "&location=" + URLEncoder.encode(location, StandardCharsets.UTF_8)
                + "&language=zh-Hans&unit=c";
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);

        // 2. 读取返回的 JSON
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        }

        // 3. 用 jackson 解析 JSON
        JsonNode root = mapper.readTree(sb.toString());

        // 心知天气报错时返回 status_code（如 key 无效 AP010003）
        if (root.has("status_code")) {
            return "天气查询失败：" + root.path("status").asText();
        }

        JsonNode results = root.path("results");
        if (!results.isArray() || results.size() == 0) {
            return "没有查到 " + location + " 的天气，换个城市试试？";
        }

        JsonNode r = results.get(0);
        JsonNode now = r.path("now");
        String city = r.path("location").path("name").asText(location);
        String text = now.path("text").asText();
        String temp = now.path("temperature").asText();
        String windDir = now.path("wind_direction").asText();
        String windScale = now.path("wind_scale").asText();
        String feelsLike = now.path("feels_like").asText();

        // 拼装描述：风向/风力/体感温度免费版可能不返回，按有无拼接，避免出现"级""体感温度 °C"空壳
        StringBuilder sb2 = new StringBuilder(city + " 当前 " + temp + "°C，" + text);
        if (!windDir.isBlank() && !windScale.isBlank()) {
            sb2.append("，").append(windDir).append(windScale).append("级");
        } else if (!windDir.isBlank()) {
            sb2.append("，").append(windDir).append("风");
        }
        if (!feelsLike.isBlank()) {
            sb2.append("，体感温度 ").append(feelsLike).append("°C");
        }
        return sb2.toString();
    }

    /** 打日志时把 key 打码，避免泄露 */
    private static String mask(String key) {
        if (key == null || key.length() < 8) {
            return "****";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
