package com.example.group_demo.tool;

import com.example.group_demo.rag.KnowledgeDocument;
import com.example.group_demo.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RestaurantTool implements BotTool {

    private final RagService ragService;

    public RestaurantTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "recommend_restaurants";
    }

    @Override
    public String description() {
        return "推荐当地特色餐厅和美食。当用户问哪里好吃、推荐餐厅、有什么特色美食时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of(
                    "type", "string",
                    "description", "城市名，如：无锡、上海"
                ),
                "cuisine_type", Map.of(
                    "type", "string",
                    "description", "菜系类型：local（本地特色）、snack（小吃快餐）、fine_dining（正餐餐厅）、dessert（甜品饮品）",
                    "enum", List.of("local", "snack", "fine_dining", "dessert")
                ),
                "budget", Map.of(
                    "type", "string",
                    "description", "人均预算：low（50以下）、mid（50-100）、high（100以上）",
                    "enum", List.of("low", "mid", "high")
                )
            ),
            "required", List.of("city"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText();
        String cuisineType = arguments.path("cuisine_type").asText("local");
        String budget = arguments.path("budget").asText("mid");

        List<KnowledgeDocument> docs = ragService.search(city + "美食餐厅");

        StringBuilder sb = new StringBuilder();
        sb.append("🍜 【").append(city).append("】美食推荐\n");
        sb.append("🍽️ 类型：").append(cuisineLabel(cuisineType)).append("  ");
        sb.append("💰 人均：").append(budgetLabel(budget)).append("\n\n");

        if (docs.isEmpty()) {
            sb.append("暂无该城市详细美食信息，建议搜索当地点评网站。");
            return sb.toString();
        }

        String content = docs.get(0).content();

        // 提取必吃名菜部分
        int dishStart = content.indexOf("【必吃名菜】");
        int dishEnd = content.indexOf("【推荐餐厅】");
        if (dishStart >= 0 && dishEnd > dishStart) {
            String dishes = content.substring(dishStart + 6, dishEnd).trim();
            sb.append("🌟 必吃名菜\n").append(dishes).append("\n\n");
        }

        // 提取推荐餐厅
        int restStart = content.indexOf("【推荐餐厅】");
        int restEnd = content.indexOf("【美食街】");
        if (restStart >= 0) {
            String restSection = restEnd > restStart
                ? content.substring(restStart + 6, restEnd).trim()
                : content.substring(restStart + 6).trim();
            sb.append("🏪 推荐餐厅\n");

            String[] lines = restSection.split("\n");
            int count = 0;
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("- ") && count < 8) {
                    sb.append(trimmed).append("\n");
                    count++;
                }
            }

            if (restEnd >= 0) {
                String streetSection = content.substring(restEnd).trim();
                sb.append("\n").append(streetSection.split("\n")[0]).append("\n");
                String[] streetLines = streetSection.split("\n");
                for (int i = 1; i < streetLines.length && i < 5; i++) {
                    if (streetLines[i].trim().startsWith("- ")) {
                        sb.append(streetLines[i].trim()).append("\n");
                    }
                }
            }
        }

        return sb.toString();
    }

    private String cuisineLabel(String type) {
        return switch (type) {
            case "local" -> "本地特色";
            case "snack" -> "小吃快餐";
            case "fine_dining" -> "正餐餐厅";
            case "dessert" -> "甜品饮品";
            default -> type;
        };
    }

    private String budgetLabel(String budget) {
        return switch (budget) {
            case "low" -> "50元以下";
            case "mid" -> "50-100元";
            case "high" -> "100元以上";
            default -> budget;
        };
    }
}
