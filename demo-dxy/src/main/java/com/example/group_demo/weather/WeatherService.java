package com.example.group_demo.weather;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.group_demo.config.RestClientFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherProperties properties;
    private final RestClient restClient;

    public WeatherService(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
    }

    public String getWeatherText(String location) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("天气 API key 未配置");
        }

        Map<String, Object> params = Map.of(
            "key", apiKey,
            "location", location,
            "language", properties.getLanguage(),
            "unit", properties.getUnit()
        );

        WeatherResponse response = restClient.get()
            .uri(properties.getBaseUrl() + "?key={key}&location={location}&language={language}&unit={unit}",
                params)
            .retrieve()
            .body(WeatherResponse.class);

        if (response == null || response.results() == null || response.results().isEmpty()
            || response.results().get(0).now() == null) {
            throw new IllegalStateException("天气接口返回为空");
        }

        WeatherResponse.Result result = response.results().get(0);
        String city = result.location() != null && result.location().name() != null
            ? result.location().name() : location;
        WeatherResponse.Now now = result.now();
        String reply = city + " 当前天气：" + now.text() + "，温度 " + now.temperature() + "℃";
        if (result.lastUpdate() != null) {
            reply += "（更新于 " + result.lastUpdate() + "）";
        }
        reply += "。出行建议：" + buildAdvice(now.text(), now.temperature()) + "祝你心情愉快～";
        log.info("天气查询成功 city={} reply={}", city, reply);
        return reply;
    }

    private String buildAdvice(String weatherText, String temperature) {
        List<String> tips = new ArrayList<>();
        String text = weatherText == null ? "" : weatherText;

        if (text.contains("雨") || text.contains("雷")) {
            tips.add("记得带伞，雨天路滑注意安全");
        }
        if (text.contains("雪")) {
            tips.add("注意保暖，路面可能湿滑");
        }
        if (text.contains("晴") || text.contains("多云") || text.contains("少云")) {
            tips.add("注意防晒，记得补水");
        }
        if (text.contains("雾") || text.contains("霾")) {
            tips.add("能见度低，出行注意安全");
        }

        try {
            int temp = (int) Double.parseDouble(temperature);
            if (temp >= 30) {
                tips.add("天气炎热，注意防暑");
            } else if (temp <= 10) {
                tips.add("天气较冷，注意保暖");
            }
        } catch (Exception ignored) {
            // 温度解析失败时忽略温度建议
        }

        if (tips.isEmpty()) {
            tips.add("注意补水，保持好心情");
        }
        return String.join("，", tips) + "。";
    }

    public record WeatherResponse(List<Result> results) {
        public record Result(Location location, Now now,
                             @JsonProperty("last_update") String lastUpdate) {
        }

        public record Location(String name) {
        }

        public record Now(String text, String temperature) {
        }
    }
}
