package com.example.group_demo.meituan;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 美团美食推荐工具：返回结构化 JSON，供旅行 Agent 渲染到网页。
 */
@Service
public class MeituanSearchRestaurantsTool implements BotTool {

    private final MeituanClient meituanClient;

    public MeituanSearchRestaurantsTool(MeituanClient meituanClient) {
        this.meituanClient = meituanClient;
    }

    @Override
    public String name() {
        return "search_restaurants";
    }

    @Override
    public String description() {
        return "通过美团开放平台查询目的地的美食推荐，返回餐厅名称、地址、人均价格、评分等信息。"
            + "旅行规划需要餐厅、美食、吃饭推荐时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of("type", "string", "description", "目的地城市，如：上海"),
                "cuisine", Map.of("type", "string", "description", "菜系或偏好，如：本帮菜，可选"),
                "budget", Map.of("type", "string", "description", "预算，如：5000，可选")
            ),
            "required", List.of("city"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText("").trim();
        if (city.isBlank()) {
            return "{\"restaurants\":[]}";
        }
        return meituanClient.searchRestaurants(
            city,
            arguments.path("cuisine").asText("").trim(),
            arguments.path("budget").asText("").trim());
    }
}
