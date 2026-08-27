package com.example.group_demo.tool;

import com.example.group_demo.rag.KnowledgeDocument;
import com.example.group_demo.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TravelTipsTool implements BotTool {

    private final RagService ragService;

    public TravelTipsTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "travel_tips";
    }

    @Override
    public String description() {
        return "提供目的地出行注意事项、最佳旅游时间、穿衣建议、省钱攻略等实用贴士。当用户问注意事项、需要带什么、旅游贴士时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "city", Map.of(
                    "type", "string",
                    "description", "目的地城市，如：无锡、上海"
                ),
                "season", Map.of(
                    "type", "string",
                    "description", "出行季节：spring、summer、autumn、winter",
                    "enum", List.of("spring", "summer", "autumn", "winter")
                ),
                "traveler_type", Map.of(
                    "type", "string",
                    "description", "出行人群：solo（独行）、couple（情侣）、family（亲子）、senior（老人）",
                    "enum", List.of("solo", "couple", "family", "senior")
                )
            ),
            "required", List.of("city"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText();
        String season = arguments.path("season").asText("");
        String travelerType = arguments.path("traveler_type").asText("");

        List<KnowledgeDocument> docs = ragService.search(city + "注意事项出行准备贴士");

        StringBuilder sb = new StringBuilder();
        sb.append("📌 【").append(city).append("】出行注意事项\n\n");

        if (docs.isEmpty()) {
            sb.append("暂无该城市的详细贴士，以下为通用出行建议：\n\n");
            sb.append(genericTips());
            return sb.toString();
        }

        String content = docs.get(0).content();

        // 提取最佳旅游时间
        if (content.contains("【最佳旅游时间】")) {
            int start = content.indexOf("【最佳旅游时间】");
            int end = content.indexOf("【出行准备】");
            if (end > start) {
                sb.append("🌤️ 最佳旅游时间\n");
                sb.append(content.substring(start + 8, end).trim()).append("\n\n");
            }
        }

        // 提取出行准备
        if (content.contains("【出行准备】")) {
            int start = content.indexOf("【出行准备】");
            int end = content.indexOf("【注意事项】");
            if (end > start) {
                sb.append("🎒 出行准备清单\n");
                sb.append(content.substring(start + 6, end).trim()).append("\n\n");
            }
        }

        // 提取注意事项
        if (content.contains("【注意事项】")) {
            int start = content.indexOf("【注意事项】");
            int end = content.indexOf("【省钱攻略】");
            if (end > start) {
                sb.append("⚠️ 注意事项\n");
                sb.append(content.substring(start + 6, end).trim()).append("\n\n");
            }
        }

        // 提取省钱攻略
        if (content.contains("【省钱攻略】")) {
            int start = content.indexOf("【省钱攻略】");
            sb.append("💰 省钱攻略\n");
            sb.append(content.substring(start + 6).trim()).append("\n");
        }

        // 根据人群追加建议
        if (!travelerType.isBlank()) {
            sb.append("\n👥 ").append(travelerLabel(travelerType)).append("特别提醒\n");
            sb.append(travelerTips(travelerType));
        }

        return sb.toString();
    }

    private String genericTips() {
        return """
            1. 提前预订车票和酒店，节假日更要早规划
            2. 带好身份证、充电宝、舒适的鞋子
            3. 提前查好天气，准备合适衣物
            4. 下载当地地铁公交APP，方便出行
            5. 准备一些常用药品：肠胃药、感冒药、创可贴
            6. 保管好贵重物品，注意人身安全
            """;
    }

    private String travelerTips(String type) {
        return switch (type) {
            case "solo" -> "• 提前告知家人行程，保持联系\n• 选择评价好的住宿，注意安全\n• 不要太晚回酒店";
            case "couple" -> "• 可以选择浪漫景观餐厅\n• 提前预订网红餐厅和酒店\n• 安排一些情侣特色体验";
            case "family" -> "• 行程不要太赶，每天2-3个景点足够\n• 带好孩子的常用药品和换洗衣物\n• 选择有家庭房的酒店";
            case "senior" -> "• 行程放慢，避免长时间步行\n• 带好常用药品和医保卡\n• 选择有电梯的酒店，楼层不要太高";
            default -> "";
        };
    }

    private String travelerLabel(String type) {
        return switch (type) {
            case "solo" -> "独自旅行";
            case "couple" -> "情侣出行";
            case "family" -> "亲子家庭";
            case "senior" -> "老人出行";
            default -> "";
        };
    }
}
