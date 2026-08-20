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
        return "get_weather";
    }

    @Override
    public String description() {
        return "获取指定城市的实时天气信息，包括气温、体感温度、天气状况、风向风力、湿度等。当用户询问天气、气温、下雨、穿什么衣服等问题时调用此工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of(
                    "type", "string",
                    "description", "城市名称，如北京、上海、广州、深圳、杭州、成都、武汉、西安等"
                )
            ),
            "required", List.of("city"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText("").trim();
        if (city.isEmpty()) {
            city = "北京";
        }
        return weatherService.getWeatherText(city);
    }
}
