package com.example.group_demo.travel;

import com.example.group_demo.image.ImageService;
import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.tool.ToolRegistry;
import com.example.group_demo.voice.VoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 旅行长任务 Agent 编排器：
 * 解析一句话目标，按顺序执行天气查询、联网搜索、RAG 检索、结构化行程生成、
 * 网页渲染、封面图/语音生成和待办写入，最终产出一份完整成品。
 */
@Service
public class TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);

    private static final String EXTRACT_SYSTEM_PROMPT = """
        你是旅行规划 Agent 的目标解析器。请从用户的旅行目标中提取信息，只输出一个 JSON 对象，不要输出任何其他内容。
        JSON 字段：
        - destination: 目的地城市或地区，未提到则为 null
        - days: 旅行天数（整数），未提到则为 null
        - budget: 预算，未提到则为 null
        - dates: 出行日期，未提到则为 null
        - travelers: 同行人数，未提到则为 null
        - preferences: 偏好，例如美食、自然、文化、亲子、夜景，未提到则为 null
        - question: 若缺少 destination 或 days，用一句中文询问最关键的缺失信息；否则为 null
        """;

    private static final String PLAN_SYSTEM_PROMPT = """
        你是旅行规划 Agent。请根据提供的资料生成完整旅行方案，只输出一个合法 JSON 对象，不要输出 markdown 代码块和任何解释。
        JSON 结构：
        {
          "destination": "目的地",
          "days": 3,
          "dates": ["4月1日", "4月2日", "4月3日"],
          "budget": {"total": "总预算", "items": [{"name": "项目", "amount": "金额"}]},
          "itinerary": [
            {
              "day": 1,
              "title": "当天主题",
              "weather": "天气摘要",
              "schedule": [{"time": "09:00", "item": "安排"}, {"time": "12:00", "item": "安排"}],
              "meals": "三餐建议",
              "hotel": "住宿建议",
              "notes": "当天注意事项"
            }
          ],
          "tips": ["出行提示1", "出行提示2", "出行提示3"],
          "mustDos": ["必做事项1", "必做事项2", "必做事项3"],
          "heroPrompt": "用于生成网页封面图的画面描述"
        }
        要求：
        1. 行程必须基于提供的资料，资料没有的信息用"需现场确认"，不得编造具体营业时间和价格。
        2. 每天 schedule 至少 4 个节点，覆盖上午、下午、晚上；同区域景点安排在同一天。
        3. tips 至少 3 条，mustDos 至少 3 条。
        """;

    private final LlmService llmService;
    private final ToolRegistry toolRegistry;
    private final KeywordRagService ragService;
    private final ImageService imageService;
    private final VoiceService voiceService;
    private final TravelPageRenderer pageRenderer;
    private final TravelProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public TravelAgentService(LlmService llmService, ToolRegistry toolRegistry,
                              KeywordRagService ragService, ImageService imageService,
                              VoiceService voiceService, TravelPageRenderer pageRenderer,
                              TravelProperties properties) {
        this.llmService = llmService;
        this.toolRegistry = toolRegistry;
        this.ragService = ragService;
        this.imageService = imageService;
        this.voiceService = voiceService;
        this.pageRenderer = pageRenderer;
        this.properties = properties;
    }

    public TravelAgentResult run(String userId, String goal) {
        if (goal == null || goal.isBlank()) {
            return TravelAgentResult.error("请先告诉我你的旅行目标，例如：帮我规划上海 3 日游。");
        }
        List<String> steps = new ArrayList<>();
        try {
            TravelRequest request = parseRequest(goal);
            if (!request.isComplete()) {
                return TravelAgentResult.needMoreInfo(request.question());
            }
            steps.add("解析旅行目标：目的地 " + request.destination() + "，行程 "
                + request.days() + " 天");

            String weather = safeTool(userId, "query_weather",
                objectMapper.createObjectNode().put("location", request.destination()).toString());
            steps.add(weather == null ? "查询目的地天气：失败" : "查询目的地天气：完成");

            StringBuilder research = new StringBuilder();
            String search1 = safeTool(userId, "web_search", searchArgs(
                request.destination() + " " + request.days() + "天旅游攻略 交通 景点 美食"));
            if (search1 != null) {
                research.append("[交通/景点/美食]\n").append(search1).append("\n\n");
            }
            String search2 = safeTool(userId, "web_search", searchArgs(
                request.destination() + " 酒店 住宿 预算 推荐"));
            if (search2 != null) {
                research.append("[住宿/预算]\n").append(search2).append("\n\n");
            }
            steps.add("联网搜索交通、景点、住宿与美食资料："
                + (research.isEmpty() ? "失败" : "完成"));

            List<KnowledgeChunk> hits = ragService.retrieve(
                request.destination() + " 旅行 攻略 行程 预算");
            String ragText = hits.isEmpty()
                ? "（本地知识库无命中）"
                : ragService.buildEnhancedPrompt(hits);
            steps.add("检索本地旅行知识库：" + (hits.isEmpty() ? "无命中" : "命中 " + hits.size() + " 条"));

            TravelPlan plan = generatePlan(request, weather, research.toString(), ragText);
            if (plan.itinerary().isEmpty()) {
                throw new IllegalStateException("LLM 未生成逐日行程");
            }
            steps.add("生成结构化行程方案：" + plan.itinerary().size() + " 天行程");

            List<TravelPlan.HotelRecommendation> hotels = fetchHotels(userId, request);
            steps.add("查询美团酒店推荐：" + (hotels.isEmpty() ? "无结果" : hotels.size() + " 家"));
            List<TravelPlan.RestaurantRecommendation> restaurants = fetchRestaurants(userId, request);
            steps.add("查询美团美食推荐：" + (restaurants.isEmpty() ? "无结果" : restaurants.size() + " 家"));
            plan = plan.withRecommendations(hotels, restaurants);

            String pageId = "trip-" + System.currentTimeMillis() + "-"
                + UUID.randomUUID().toString().substring(0, 6);
            boolean imageGenerated = tryGenerateImage(plan, pageId);
            if (imageGenerated) {
                steps.add("生成旅行封面图：完成");
            }
            boolean voiceGenerated = tryGenerateVoice(plan, pageId);
            if (voiceGenerated) {
                steps.add("生成语音摘要：完成");
            }

            String htmlUrl = savePage(plan, pageId);
            steps.add("渲染并保存旅行网页");

            int todoCount = writeTodos(userId, plan);
            steps.add("写入每日待办：" + todoCount + " 条");

            String reply = buildReply(plan, steps, htmlUrl, todoCount, imageGenerated, voiceGenerated);
            return new TravelAgentResult("done", null, reply, htmlUrl, pageId, plan,
                steps, todoCount, imageGenerated, voiceGenerated);
        } catch (Exception e) {
            log.error("旅行 Agent 执行失败 userId={} goal={}", userId, goal, e);
            return TravelAgentResult.error("旅行规划执行失败：" + e.getMessage());
        }
    }

    private TravelRequest parseRequest(String goal) {
        String raw = llmService.chatRaw(EXTRACT_SYSTEM_PROMPT, goal);
        JsonNode node = TravelJsonParser.extract(raw);
        Integer days = null;
        if (node.hasNonNull("days") && !node.path("days").isMissingNode()) {
            int value = node.path("days").asInt(0);
            if (value > 0) {
                days = value;
            }
        }
        return new TravelRequest(
            TravelJsonParser.text(node, "destination"),
            days,
            TravelJsonParser.text(node, "budget"),
            TravelJsonParser.text(node, "dates"),
            TravelJsonParser.text(node, "travelers"),
            TravelJsonParser.text(node, "preferences"),
            TravelJsonParser.text(node, "question"));
    }

    private TravelPlan generatePlan(TravelRequest request, String weather,
                                    String research, String ragText) {
        String userPrompt = "请为" + request.destination() + "生成" + request.days()
            + "天旅行方案。\n\n"
            + "出行约束：预算 " + orUnknown(request.budget()) + "；日期 "
            + orUnknown(request.dates()) + "；同行人 " + orUnknown(request.travelers())
            + "；偏好 " + orUnknown(request.preferences()) + "。\n\n"
            + "天气资料：\n" + (weather == null ? "（查询失败）" : weather) + "\n\n"
            + "联网检索资料：\n" + (research.isBlank() ? "（无）" : research) + "\n\n"
            + "本地知识库资料：\n" + ragText;
        String raw = llmService.chatRaw(PLAN_SYSTEM_PROMPT, userPrompt);
        try {
            return TravelPlan.fromJson(TravelJsonParser.extract(raw));
        } catch (Exception first) {
            log.warn("首次行程 JSON 解析失败，重试一次：{}", first.getMessage());
            String retry = "你上一次的输出不是合法 JSON。请只输出一个符合要求的 JSON 对象。\n\n"
                + "上一次输出：\n" + raw;
            String retryRaw = llmService.chatRaw(PLAN_SYSTEM_PROMPT, retry);
            return TravelPlan.fromJson(TravelJsonParser.extract(retryRaw));
        }
    }

    private String safeTool(String userId, String toolName, String arguments) {
        try {
            return toolRegistry.executeStrict(userId, toolName, arguments);
        } catch (Exception e) {
            log.warn("旅行 Agent 工具调用失败 tool={} arguments={}", toolName, arguments, e);
            return null;
        }
    }

    private String searchArgs(String query) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("query", query);
        node.put("max_results", 6);
        return node.toString();
    }

    private List<TravelPlan.HotelRecommendation> fetchHotels(String userId, TravelRequest request) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("city", request.destination());
        args.put("check_in", "");
        args.put("check_out", "");
        args.put("budget", orUnknown(request.budget()));
        return parseHotels(safeTool(userId, "search_hotels", args.toString()));
    }

    private List<TravelPlan.RestaurantRecommendation> fetchRestaurants(String userId,
                                                                       TravelRequest request) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("city", request.destination());
        args.put("cuisine", orUnknown(request.preferences()));
        args.put("budget", orUnknown(request.budget()));
        return parseRestaurants(safeTool(userId, "search_restaurants", args.toString()));
    }

    private List<TravelPlan.HotelRecommendation> parseHotels(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json).path("hotels");
            List<TravelPlan.HotelRecommendation> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    result.add(TravelPlan.HotelRecommendation.fromJson(item));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("美团酒店推荐解析失败", e);
            return List.of();
        }
    }

    private List<TravelPlan.RestaurantRecommendation> parseRestaurants(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            JsonNode node = objectMapper.readTree(json).path("restaurants");
            List<TravelPlan.RestaurantRecommendation> result = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode item : node) {
                    result.add(TravelPlan.RestaurantRecommendation.fromJson(item));
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("美团美食推荐解析失败", e);
            return List.of();
        }
    }

    private boolean tryGenerateImage(TravelPlan plan, String pageId) {
        if (!properties.isGenerateImage() || imageService == null) {
            return false;
        }
        try {
            String prompt = plan.heroPrompt() == null || plan.heroPrompt().isBlank()
                ? plan.destination() + " 旅游风景封面，明亮插画风格"
                : plan.heroPrompt();
            byte[] png = imageService.generateImage(prompt);
            Files.createDirectories(pageDir());
            Path target = pageDir().resolve(pageId + "-hero.png");
            Files.write(target, png);
            return true;
        } catch (Exception e) {
            log.warn("旅行封面图生成失败，继续生成网页", e);
            return false;
        }
    }

    private boolean tryGenerateVoice(TravelPlan plan, String pageId) {
        if (!properties.isGenerateVoice() || voiceService == null) {
            return false;
        }
        try {
            byte[] mp3 = voiceService.synthesizeToMp3(buildVoiceSummary(plan));
            Files.createDirectories(pageDir());
            Files.write(pageDir().resolve(pageId + ".mp3"), mp3);
            return true;
        } catch (Exception e) {
            log.warn("语音摘要生成失败，继续生成网页", e);
            return false;
        }
    }

    private String savePage(TravelPlan plan, String pageId) throws IOException {
        Path dir = pageDir();
        Files.createDirectories(dir);
        String heroSrc = Files.exists(dir.resolve(pageId + "-hero.png"))
            ? "./" + pageId + "-hero.png" : null;
        String voiceSrc = Files.exists(dir.resolve(pageId + ".mp3"))
            ? "./" + pageId + ".mp3" : null;
        String html = pageRenderer.render(plan, pageId, heroSrc, voiceSrc);
        Files.writeString(dir.resolve(pageId + ".html"), html, StandardCharsets.UTF_8);
        return properties.getPageBaseUrl() + "/" + pageId + ".html";
    }

    private int writeTodos(String userId, TravelPlan plan) {
        int count = 0;
        for (TravelPlan.DayPlan day : plan.itinerary()) {
            StringBuilder text = new StringBuilder();
            text.append("Day ").append(Math.max(1, day.day()));
            if (day.title() != null && !day.title().isBlank()) {
                text.append(" ").append(day.title());
            }
            text.append("：");
            List<String> highlights = day.schedule().stream()
                .limit(3)
                .map(slot -> slot.time() + " " + slot.item())
                .toList();
            if (!highlights.isEmpty()) {
                text.append(String.join("；", highlights));
            }
            if (day.hotel() != null && !day.hotel().isBlank()) {
                text.append("（住宿：").append(day.hotel()).append("）");
            }
            ObjectNode args = objectMapper.createObjectNode();
            args.put("action", "add");
            args.put("text", text.toString());
            if (safeTool(userId, "manage_todo", args.toString()) != null) {
                count++;
            }
        }
        for (String mustDo : plan.mustDos()) {
            ObjectNode args = objectMapper.createObjectNode();
            args.put("action", "add");
            args.put("text", mustDo);
            if (safeTool(userId, "manage_todo", args.toString()) != null) {
                count++;
            }
        }
        return count;
    }

    private String buildVoiceSummary(TravelPlan plan) {
        StringBuilder sb = new StringBuilder();
        sb.append(plan.destination()).append(plan.days()).append("日游完整方案。");
        if (!plan.dates().isEmpty()) {
            sb.append("出行时间").append(String.join("、", plan.dates())).append("。");
        }
        if (plan.budget() != null && plan.budget().total() != null && !plan.budget().total().isBlank()) {
            sb.append("预算约").append(money(plan.budget().total())).append("。");
        }
        if (!plan.itinerary().isEmpty()) {
            TravelPlan.DayPlan first = plan.itinerary().get(0);
            if (first.title() != null && !first.title().isBlank()) {
                sb.append("第一天主题是").append(first.title()).append("。");
            }
        }
        sb.append("每日安排和必做事项已写入待办，网页版方案可以随时查看，祝你旅途愉快。");
        return sb.toString();
    }

    private String buildReply(TravelPlan plan, List<String> steps, String htmlUrl,
                              int todoCount, boolean imageGenerated, boolean voiceGenerated) {
        StringBuilder reply = new StringBuilder();
        reply.append("已为你完成 ").append(plan.destination()).append(plan.days())
            .append(" 日游完整规划\n\nAgent 自动执行步骤：\n");
        for (int i = 0; i < steps.size(); i++) {
            reply.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        reply.append("\n网页版完整方案：").append(htmlUrl).append("\n");
        reply.append("每日必做事项已写入待办，共 ").append(todoCount).append(" 条。\n");
        if (imageGenerated) {
            reply.append("已生成旅行封面图，可在网页中查看。\n");
        }
        if (voiceGenerated) {
            reply.append("已生成语音摘要，可在网页中播放。\n");
        }
        if (plan.budget() != null && plan.budget().total() != null && !plan.budget().total().isBlank()) {
            reply.append("\n预算约 ").append(money(plan.budget().total()))
                .append("，出行前请再次确认门票、酒店和天气。");
        }
        return reply.toString();
    }

    private String money(String text) {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) {
            return "待确认";
        }
        return value.matches(".*[元¥$￥].*") ? value : value + " 元";
    }

    private Path pageDir() {
        return Path.of(properties.getPageDir()).toAbsolutePath().normalize();
    }

    private String orUnknown(String value) {
        return value == null || value.isBlank() ? "未指定" : value;
    }

    private record TravelRequest(String destination, Integer days, String budget,
                                 String dates, String travelers, String preferences,
                                 String question) {

        boolean isComplete() {
            return destination != null && !destination.isBlank()
                && days != null && days > 0;
        }
    }
}
