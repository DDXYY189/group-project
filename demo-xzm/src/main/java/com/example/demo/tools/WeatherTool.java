package com.example.demo.tools;

import com.example.demo.llm.WeatherService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具：调用 WeatherService 获取实时天气数据。
 *
 * LLM 看到此工具描述后，当用户问"上海天气"时，会自动决定调用：
 *   get_weather(city="上海")
 * 工具返回天气数据，LLM 再用自然语言回复用户。
 */
@Component
public class WeatherTool implements BotTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public String getName() {
        return "get_weather";
    }

    @Override
    public String getDescription() {
        return "获取指定城市的实时天气信息，包括气温、体感温度、天气状况、风向风力、湿度等。当用户询问天气、气温、下雨、穿什么衣服等问题时调用此工具。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "city", Map.of(
                                "type", "string",
                                "description", "城市名称，如北京、上海、广州、深圳、杭州、成都、武汉、西安等"
                        )
                ),
                "required", List.of("city")
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        String city = arguments != null ? (String) arguments.get("city") : null;
        if (city == null || city.isBlank()) {
            city = "北京";
        }
        return weatherService.getWeather(city);
    }
}
