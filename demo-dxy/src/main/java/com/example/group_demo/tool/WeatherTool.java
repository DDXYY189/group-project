package com.example.group_demo.tool;

import com.example.group_demo.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具：查询指定城市实时天气 + 3天预报 + 出行建议。
 * 心知天气(Seniverse) 为主，Open-Meteo 为备用源。
 */
@Service
public class WeatherTool implements BotTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String name() {
        return "query_weather";
    }

    @Override
    public String description() {
        return "查询指定城市的实时天气和3天预报，包括当前温度、天气状况、湿度、风向、"
                + "未来3天预报和出行建议。当用户询问天气、气温、穿什么衣服、是否需要带伞时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "location", Map.of(
                    "type", "string",
                    "description", "城市名，例如：北京、上海、广州、深圳、杭州、沭阳、宿迁等"
                )
            ),
            "required", List.of("location"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String location = arguments.path("location").asText("").trim();
        if (location.isEmpty()) {
            throw new IllegalArgumentException("缺少 location 参数");
        }
        return weatherService.getWeatherText(location);
    }
}
