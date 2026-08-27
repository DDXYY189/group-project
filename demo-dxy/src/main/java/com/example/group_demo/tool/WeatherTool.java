package com.example.group_demo.tool;

import com.example.group_demo.weather.WeatherService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

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
        return "查询指定城市当前的天气和温度，返回中文文本。当用户询问某个城市的天气时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "location", Map.of(
                    "type", "string",
                    "description", "城市名，例如：北京、上海、广州"
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
