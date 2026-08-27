package com.example.group_demo.tool;

import com.example.group_demo.rag.KnowledgeDocument;
import com.example.group_demo.rag.RagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class HotelTool implements BotTool {

    private final RagService ragService;

    public HotelTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public String name() {
        return "recommend_hotels";
    }

    @Override
    public String description() {
        return "根据目的地、预算和偏好推荐合适的酒店和住宿方案。当用户需要订酒店、找住宿、推荐宾馆时调用。";
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
                "budget", Map.of(
                    "type", "string",
                    "description", "预算档次：economy（经济型200以下）、midrange（中档200-500）、upscale（高档500-1000）、luxury（豪华1000以上）",
                    "enum", List.of("economy", "midrange", "upscale", "luxury")
                ),
                "area", Map.of(
                    "type", "string",
                    "description", "偏好区域：center（市中心）、scenic（景区附近）、nightlife（美食街/夜生活）、resort（度假休闲）",
                    "enum", List.of("center", "scenic", "nightlife", "resort")
                ),
                "check_in", Map.of(
                    "type", "string",
                    "description", "入住日期，YYYY-MM-DD"
                ),
                "check_out", Map.of(
                    "type", "string",
                    "description", "离店日期，YYYY-MM-DD"
                )
            ),
            "required", List.of("city"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String city = arguments.path("city").asText();
        String budget = arguments.path("budget").asText("midrange");
        String area = arguments.path("area").asText("center");
        String checkIn = arguments.path("check_in").asText("");
        String checkOut = arguments.path("check_out").asText("");

        List<KnowledgeDocument> docs = ragService.search(city + "住宿酒店");

        StringBuilder sb = new StringBuilder();
        sb.append("🏨 【").append(city).append("】住宿推荐\n");
        sb.append("💰 预算：").append(budgetLabel(budget)).append("  ");
        sb.append("📍 偏好：").append(areaLabel(area));
        if (!checkIn.isBlank()) {
            sb.append("  📅 ").append(checkIn);
            if (!checkOut.isBlank()) sb.append(" ~ ").append(checkOut);
        }
        sb.append("\n\n");

        if (docs.isEmpty()) {
            sb.append("暂无该城市的详细酒店信息，以下为通用推荐：\n\n");
            sb.append(buildGenericRecommendations(city, budget, area));
        } else {
            // 从知识库提取酒店信息
            String content = docs.get(0).content();
            String[] sections = content.split("【");
            List<String> matched = new ArrayList<>();
            for (String section : sections) {
                if (section.isBlank()) continue;
                String sectionLower = section.toLowerCase();
                if (matchesBudget(section, budget) || matchesArea(section, area)) {
                    matched.add(section);
                }
            }
            if (matched.isEmpty()) {
                // 没匹配到就返回前两条酒店信息
                int count = 0;
                for (String section : sections) {
                    if (section.contains("：") && section.contains("参考价")) {
                        sb.append("• ").append(section.split("\n")[0].trim()).append("\n");
                        count++;
                        if (count >= 5) break;
                    }
                }
            } else {
                for (String m : matched) {
                    String[] lines = m.split("\n");
                    if (lines.length > 0) {
                        sb.append("• ").append(lines[0].trim()).append("\n");
                    }
                }
            }
        }

        sb.append("\n💡 建议：提前 2-4 周预订，节假日价格会上涨");

        return sb.toString();
    }

    private String buildGenericRecommendations(String city, String budget, String area) {
        StringBuilder sb = new StringBuilder();
        String priceRange = switch (budget) {
            case "economy" -> "150-250元/晚";
            case "midrange" -> "300-500元/晚";
            case "upscale" -> "500-1000元/晚";
            case "luxury" -> "1000元/晚以上";
            default -> "300-500元/晚";
        };
        String areaHint = switch (area) {
            case "center" -> "市中心交通便利，推荐全季、亚朵等连锁品牌";
            case "scenic" -> "景区附近方便游玩，推荐当地特色民宿";
            case "nightlife" -> "美食街附近，晚上逛街吃饭方便";
            case "resort" -> "度假区酒店，适合休闲放松";
            default -> "根据行程选择合适位置";
        };
        sb.append("价格区间：").append(priceRange).append("\n");
        sb.append("区域建议：").append(areaHint).append("\n");
        return sb.toString();
    }

    private boolean matchesBudget(String section, String budget) {
        String lower = section.toLowerCase();
        return switch (budget) {
            case "economy" -> lower.contains("经济型") || lower.contains("200") || lower.contains("300");
            case "midrange" -> lower.contains("中档") || lower.contains("300") || lower.contains("400") || lower.contains("500");
            case "upscale" -> lower.contains("五星级") || lower.contains("500") || lower.contains("600") || lower.contains("900");
            case "luxury" -> lower.contains("豪华") || lower.contains("精品") || lower.contains("1000");
            default -> true;
        };
    }

    private boolean matchesArea(String section, String area) {
        String lower = section.toLowerCase();
        return switch (area) {
            case "center" -> lower.contains("市中心") || lower.contains("中山路") || lower.contains("三阳广场");
            case "scenic" -> lower.contains("太湖") || lower.contains("鼋头渚") || lower.contains("灵山") || lower.contains("景区");
            case "nightlife" -> lower.contains("南长街") || lower.contains("古运河") || lower.contains("美食街");
            case "resort" -> lower.contains("度假") || lower.contains("温泉") || lower.contains("拈花湾");
            default -> true;
        };
    }

    private String budgetLabel(String budget) {
        return switch (budget) {
            case "economy" -> "经济型";
            case "midrange" -> "中档";
            case "upscale" -> "高档";
            case "luxury" -> "豪华";
            default -> budget;
        };
    }

    private String areaLabel(String area) {
        return switch (area) {
            case "center" -> "市中心";
            case "scenic" -> "景区附近";
            case "nightlife" -> "美食街/夜生活";
            case "resort" -> "度假休闲";
            default -> area;
        };
    }
}
