package com.example.group_demo.agent;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.tool.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 旅游规划 Agent：从用户一句话需求出发，自动拆解 6 个子任务，
 * 依次调用天气、车票、酒店、餐厅、行程、注意事项工具，
 * 最终汇总输出一份完整的旅游方案。
 */
@Service
public class TravelPlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(TravelPlannerAgent.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;

    public TravelPlannerAgent(LlmService llmService, ToolRegistry toolRegistry) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 主入口：用户输入一句话，返回完整旅游方案。
     */
    public String plan(String userId, String userRequest) {
        log.info("旅游规划 Agent 启动 userId={} request={}", userId, userRequest);

        // 步骤1：LLM 提取参数（任务拆解）
        TravelParams params = extractParams(userRequest);
        log.info("参数提取完成 destination={} days={} fromCity={}",
            params.destination(), params.days(), params.fromCity());

        // 步骤2-7：依次执行 6 个子任务
        List<SubTaskResult> results = new ArrayList<>();

        // 子任务1：天气查询
        results.add(executeWeatherTask(params));

        // 子任务2：车票查询
        results.add(executeTrainTicketTask(params));

        // 子任务3：酒店推荐
        results.add(executeHotelTask(params));

        // 子任务4：餐厅推荐
        results.add(executeRestaurantTask(params));

        // 子任务5：行程规划
        results.add(executeItineraryTask(params));

        // 子任务6：出行注意事项
        results.add(executeTravelTipsTask(params));

        // 步骤8：汇总输出完整方案
        String finalPlan = assemblePlan(params, results);
        log.info("旅游规划完成，共 {} 个子任务", results.size());

        return finalPlan;
    }

    // ========== 参数提取 ==========

    private TravelParams extractParams(String userRequest) {
        String prompt = """
            你是一个旅游规划助手。请从用户的旅游需求中提取以下信息，输出 JSON 格式：
            - destination: 目的地城市（必填）
            - days: 游玩天数，整数（必填，默认3）
            - from_city: 出发城市（可选，不知道则填"上海"）
            - depart_date: 出发日期，格式 YYYY-MM-DD（可选，默认7天后）
            - budget: 预算档次，可选值 economy/midrange/upscale/luxury（默认 midrange）
            - style: 行程风格，可选值 classic/relaxed/culture/foodie（默认 classic）
            - traveler_type: 出行人群，可选值 solo/couple/family/senior（默认 couple）

            用户需求：%s

            只输出 JSON，不要其他内容。
            """.formatted(userRequest);

        try {
            String jsonStr = llmService.chatRaw("你是旅游参数提取助手，只输出JSON。", prompt);
            // 清理可能的 markdown 标记
            jsonStr = jsonStr.replaceAll("```json", "").replaceAll("```", "").trim();
            JsonNode json = MAPPER.readTree(jsonStr);

            String destination = json.path("destination").asText("无锡");
            int days = json.path("days").asInt(3);
            String fromCity = json.path("from_city").asText("上海");
            String departDate = json.path("depart_date").asText("");
            if (departDate.isBlank()) {
                departDate = LocalDate.now().plusDays(7).format(DATE_FMT);
            }
            String budget = json.path("budget").asText("midrange");
            String style = json.path("style").asText("classic");
            String travelerType = json.path("traveler_type").asText("couple");

            String returnDate = LocalDate.parse(departDate).plusDays(days).format(DATE_FMT);

            return new TravelParams(destination, days, fromCity, departDate, returnDate,
                budget, style, travelerType);
        } catch (Exception e) {
            log.warn("LLM 参数提取失败，使用默认参数: {}", e.getMessage());
            String departDate = LocalDate.now().plusDays(7).format(DATE_FMT);
            String returnDate = LocalDate.parse(departDate).plusDays(3).format(DATE_FMT);
            return new TravelParams("无锡", 3, "上海", departDate, returnDate,
                "midrange", "classic", "couple");
        }
    }

    // ========== 子任务执行 ==========

    private SubTaskResult executeWeatherTask(TravelParams params) {
        try {
            String args = MAPPER.writeValueAsString(Map.of("location", params.destination()));
            String result = toolRegistry.execute("plan_agent", "query_weather", args);
            return new SubTaskResult("天气查询", "☀️", result, true);
        } catch (Exception e) {
            return new SubTaskResult("天气查询", "☀️", "天气查询暂时不可用：" + e.getMessage(), false);
        }
    }

    private SubTaskResult executeTrainTicketTask(TravelParams params) {
        try {
            Map<String, String> argsMap = new LinkedHashMap<>();
            argsMap.put("from_city", params.fromCity());
            argsMap.put("to_city", params.destination());
            argsMap.put("depart_date", params.departDate());
            argsMap.put("return_date", params.returnDate());
            argsMap.put("preference", "time");
            String args = MAPPER.writeValueAsString(argsMap);
            String result = toolRegistry.execute("plan_agent", "search_train_tickets", args);
            return new SubTaskResult("往返车票", "🚄", result, true);
        } catch (Exception e) {
            return new SubTaskResult("往返车票", "🚄", "车票查询暂时不可用：" + e.getMessage(), false);
        }
    }

    private SubTaskResult executeHotelTask(TravelParams params) {
        try {
            Map<String, String> argsMap = new LinkedHashMap<>();
            argsMap.put("city", params.destination());
            argsMap.put("budget", params.budget());
            argsMap.put("area", "center");
            argsMap.put("check_in", params.departDate());
            argsMap.put("check_out", params.returnDate());
            String args = MAPPER.writeValueAsString(argsMap);
            String result = toolRegistry.execute("plan_agent", "recommend_hotels", args);
            return new SubTaskResult("酒店推荐", "🏨", result, true);
        } catch (Exception e) {
            return new SubTaskResult("酒店推荐", "🏨", "酒店推荐暂时不可用：" + e.getMessage(), false);
        }
    }

    private SubTaskResult executeRestaurantTask(TravelParams params) {
        try {
            String args = MAPPER.writeValueAsString(Map.of(
                "city", params.destination(),
                "cuisine_type", "local",
                "budget", "mid"
            ));
            String result = toolRegistry.execute("plan_agent", "recommend_restaurants", args);
            return new SubTaskResult("美食推荐", "🍜", result, true);
        } catch (Exception e) {
            return new SubTaskResult("美食推荐", "🍜", "美食推荐暂时不可用：" + e.getMessage(), false);
        }
    }

    private SubTaskResult executeItineraryTask(TravelParams params) {
        try {
            String args = MAPPER.writeValueAsString(Map.of(
                "city", params.destination(),
                "days", params.days(),
                "style", params.style()
            ));
            String result = toolRegistry.execute("plan_agent", "plan_itinerary", args);
            return new SubTaskResult("行程规划", "🗺️", result, true);
        } catch (Exception e) {
            return new SubTaskResult("行程规划", "🗺️", "行程规划暂时不可用：" + e.getMessage(), false);
        }
    }

    private SubTaskResult executeTravelTipsTask(TravelParams params) {
        try {
            Map<String, String> argsMap = new LinkedHashMap<>();
            argsMap.put("city", params.destination());
            argsMap.put("season", getSeason(params.departDate()));
            argsMap.put("traveler_type", params.travelerType());
            String args = MAPPER.writeValueAsString(argsMap);
            String result = toolRegistry.execute("plan_agent", "travel_tips", args);
            return new SubTaskResult("出行贴士", "📌", result, true);
        } catch (Exception e) {
            return new SubTaskResult("出行贴士", "📌", "出行贴士暂时不可用：" + e.getMessage(), false);
        }
    }

    // ========== 汇总输出 ==========

    private String assemblePlan(TravelParams params, List<SubTaskResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("✈️ ").append(params.destination()).append(params.days()).append("日游完整方案 ✈️\n");
        sb.append("═══════════════════════════════\n\n");

        sb.append("📋 行程概览\n");
        sb.append("• 目的地：").append(params.destination()).append("\n");
        sb.append("• 天数：").append(params.days()).append(" 天\n");
        sb.append("• 出发地：").append(params.fromCity()).append("\n");
        sb.append("• 日期：").append(params.departDate()).append(" ~ ").append(params.returnDate()).append("\n");
        sb.append("• 预算：").append(budgetLabel(params.budget())).append("\n");
        sb.append("• 风格：").append(styleLabel(params.style())).append("\n");
        sb.append("• 出行人群：").append(travelerLabel(params.travelerType())).append("\n\n");

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\n");

        for (SubTaskResult result : results) {
            sb.append(result.emoji()).append(" ").append(result.title()).append("\n");
            sb.append("───────────────────────────────\n");
            sb.append(result.content()).append("\n\n");
        }

        sb.append("═══════════════════════════════\n");
        sb.append("🎯 祝您旅途愉快！有任何需要调整的地方随时告诉我~\n");
        sb.append("（本方案由旅游规划 Agent 自动生成，信息仅供参考）");

        return sb.toString();
    }

    // ========== 辅助方法 ==========

    private String getSeason(String dateStr) {
        try {
            LocalDate date = LocalDate.parse(dateStr);
            int month = date.getMonthValue();
            return switch (month) {
                case 3, 4, 5 -> "spring";
                case 6, 7, 8 -> "summer";
                case 9, 10, 11 -> "autumn";
                default -> "winter";
            };
        } catch (Exception e) {
            return "autumn";
        }
    }

    private String budgetLabel(String budget) {
        return switch (budget) {
            case "economy" -> "经济型（200元以下/晚）";
            case "midrange" -> "中档（200-500元/晚）";
            case "upscale" -> "高档（500-1000元/晚）";
            case "luxury" -> "豪华（1000元以上/晚）";
            default -> budget;
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

    private String travelerLabel(String type) {
        return switch (type) {
            case "solo" -> "独自旅行";
            case "couple" -> "情侣出行";
            case "family" -> "亲子家庭";
            case "senior" -> "老人出行";
            default -> type;
        };
    }

    // ========== 内部记录类 ==========

    record TravelParams(String destination, int days, String fromCity,
                        String departDate, String returnDate,
                        String budget, String style, String travelerType) {}

    record SubTaskResult(String title, String emoji, String content, boolean success) {}
}
