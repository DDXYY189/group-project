package com.example.demo.weather;

import com.example.demo.config.WeatherProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;

@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private final WeatherProperties properties;
    private final RestClient restClient;

    public WeatherService(WeatherProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    public String now(String location) {
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            return "还没有配置心知天气 API Key，请在环境变量或配置中设置 seniverse.api-key。";
        }

        try {
            String uri = UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                    .queryParam("key", apiKey)
                    .queryParam("location", location)
                    .queryParam("language", "zh-Hans")
                    .queryParam("unit", "c")
                    .toUriString();

            JsonNode response = restClient.get()
                    .uri(uri)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode now = response.path("results").path(0).path("now");
            String text = now.path("text").asText("");
            String temperature = now.path("temperature").asText("");
            String feelsLike = now.path("feels_like").asText("");

            if (text.isBlank()) {
                return "没有查到「" + location + "」的天气信息。";
            }

            return "「" + location + "」当前天气：" + text
                    + "，气温 " + temperature + "℃"
                    + (feelsLike.isBlank() ? "" : "，体感 " + feelsLike + "℃")
                    + "。\n（数据来源：心知天气）";
        } catch (Exception e) {
            log.error("查询天气失败", e);
            return "查询天气失败：" + e.getMessage();
        }
    }
}
