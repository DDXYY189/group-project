package com.example.demo.tool;

import com.example.demo.weather.WeatherService;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * 天气工具：把心知天气的实时查询能力暴露给大模型。
 */
@Component
public class WeatherTool implements Tool {

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
        return "查询指定城市的实时天气，返回天气现象、气温和体感温度。用户询问天气时使用。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "城市名称，例如：南京、苏州、北京")),
                "required", List.of("city"));
    }

    @Override
    public String execute(JsonNode arguments) {
        String city = arguments.path("city").asText("");
        if (city.isBlank()) {
            return "没有提供城市名称。";
        }
        return weatherService.now(city.trim());
    }
}
