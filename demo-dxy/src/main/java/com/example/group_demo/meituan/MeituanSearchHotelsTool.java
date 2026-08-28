package com.example.group_demo.meituan;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 美团酒店推荐工具：返回结构化 JSON，供旅行 Agent 渲染到网页。
 */
@Service
public class MeituanSearchHotelsTool implements BotTool {

    private final MeituanClient meituanClient;

    public MeituanSearchHotelsTool(MeituanClient meituanClient) {
        this.meituanClient = meituanClient;
    }

    @Override
    public String name() {
        return "search_hotels";
    }

    @Override
    public String description() {
        return "通过美团开放平台查询目的地的酒店推荐，返回酒店名称、地址、价格、评分等信息。"
            + "旅行规划需要酒店、住宿推荐时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of("type", "string", "description", "目的地城市，如：上海"),
                "check_in", Map.of("type", "string", "description", "入住日期 YYYY-MM-DD，可选"),
                "check_out", Map.of("type", "string", "description", "离店日期 YYYY-MM-DD，可选"),
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
            return "{\"hotels\":[]}";
        }
        return meituanClient.searchHotels(
            city,
            arguments.path("check_in").asText("").trim(),
            arguments.path("check_out").asText("").trim(),
            arguments.path("budget").asText("").trim());
    }
}
