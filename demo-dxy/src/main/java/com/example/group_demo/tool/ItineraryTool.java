package com.example.group_demo.tool;

import com.example.group_demo.rag.KnowledgeDocument;
import com.example.group_demo.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ItineraryTool implements BotTool {

    private final RagService ragService;

    public ItineraryTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "plan_itinerary";
    }

    @Override
    public String description() {
        return "根据目的地和天数规划详细的每日行程安排，包括景点、餐饮、交通。当用户需要旅游攻略、行程规划、怎么玩时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of(
                    "type", "string",
                    "description", "目的地城市，如：无锡、杭州"
                ),
                "days", Map.of(
                    "type", "integer",
                    "description", "游玩天数，如 1、2、3"
                ),
                "style", Map.of(
                    "type", "string",
                    "description", "行程风格：classic（经典打卡）、relaxed（休闲度假）、culture（文化深度）、foodie（美食之旅）",
                    "enum", List.of("classic", "relaxed", "culture", "foodie")
                )
            ),
            "required", List.of("city", "days"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText();
        int days = arguments.path("days").asInt(3);
        String style = arguments.path("style").asText("classic");

        List<KnowledgeDocument> docs = ragService.search(city + "行程攻略景点");

        StringBuilder sb = new StringBuilder();
        sb.append("🗺️ 【").append(city).append(days).append("日游】行程规划\n");
        sb.append("🎯 风格：").append(styleLabel(style)).append("\n\n");

        if (docs.isEmpty()) {
            sb.append("暂无该城市的详细行程信息，以下为通用行程框架：\n\n");
            for (int i = 1; i <= days; i++) {
                sb.append("📅 Day ").append(i).append("\n");
                sb.append("上午：市区景点游览\n");
                sb.append("中午：当地特色午餐\n");
                sb.append("下午：近郊景点\n");
                sb.append("晚上：夜市/步行街\n\n");
            }
            return sb.toString();
        }

        // 从知识库中提取行程和景点信息
        String itineraryContent = "";
        String attractionContent = "";
        for (KnowledgeDocument doc : docs) {
            if (doc.title().contains("行程")) {
                itineraryContent = doc.content();
            } else if (doc.title().contains("景点")) {
                attractionContent = doc.content();
            }
        }

        if (!itineraryContent.isBlank() && days == 3) {
            // 提取3日游行程
            sb.append(extractItinerary(itineraryContent));
        } else {
            // 根据天数生成行程
            sb.append(generateItinerary(city, days, style, attractionContent));
        }

        sb.append("\n💡 行程小贴士\n");
        sb.append("• 建议早上 8-9 点出发，避开人流高峰\n");
        sb.append("• 午餐建议 11:30 前到餐厅，避开用餐高峰\n");
        sb.append("• 带好充电宝、遮阳伞、舒适运动鞋\n");
        sb.append("• 热门景点建议提前网上预约购票");

        return sb.toString();
    }

    private String extractItinerary(String content) {
        StringBuilder sb = new StringBuilder();
        String[] lines = content.split("\n");
        boolean inItinerary = false;
        for (String line : lines) {
            if (line.contains("Day 1") || line.contains("Day 2") || line.contains("Day 3")) {
                inItinerary = true;
            }
            if (inItinerary) {
                if (line.startsWith("【") && !line.contains("Day")) {
                    break;
                }
                if (!line.isBlank()) {
                    // 美化格式
                    String formatted = line
                        .replace("【Day", "📅 Day")
                        .replace("：】", "】")
                        .replace("- 上午：", "  🌅 上午：")
                        .replace("- 中午：", "  🍱 中午：")
                        .replace("- 下午：", "  🌆 下午：")
                        .replace("- 傍晚：", "  🌇 傍晚：")
                        .replace("- 晚上：", "  🌙 晚上：")
                        .replace("- 住宿：", "  🏨 住宿：");
                    sb.append(formatted).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String generateItinerary(String city, int days, String style, String attractions) {
        StringBuilder sb = new StringBuilder();
        String[] dayThemes = getDayThemes(style, days);

        for (int i = 0; i < days; i++) {
            sb.append("📅 Day ").append(i + 1).append("（").append(dayThemes[i]).append("）\n");
            sb.append("  🌅 上午：核心景点游览\n");
            sb.append("  🍱 中午：当地特色餐厅\n");
            sb.append("  🌆 下午：周边景点/体验活动\n");
            sb.append("  🌙 晚上：夜景/美食街\n\n");
        }

        return sb.toString();
    }

    private String[] getDayThemes(String style, int days) {
        return switch (style) {
            case "classic" -> new String[]{"经典打卡", "自然山水", "文化古镇"};
            case "relaxed" -> new String[]{"慢游市区", "度假休闲", "美食探店"};
            case "culture" -> new String[]{"历史文化", "艺术博物馆", "民俗体验"};
            case "foodie" -> new String[]{"老字号探店", "小吃街扫街", "特色餐厅"};
            default -> new String[]{"城市观光", "周边游", "休闲购物"};
        };
    }

    private String styleLabel(String style) {
        return switch (style) {
            case "classic" -> "经典打卡";
            case "relaxed" -> "休闲度假";
            case "culture" -> "文化深度";
            case "foodie" -> "美食之旅";
            default -> style;
        };
    }
}
