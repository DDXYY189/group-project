package com.example.demo_wkx.skill;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 自定义 Skill 服务
 *
 * Skill 与 Function Calling 的区别：
 * - Function Calling: LLM 自主决定是否调用工具，灵活性高但依赖 LLM 推理能力
 * - Skill: 基于关键词匹配，命中后直接执行，无需 LLM 参与，响应快、确定性高
 *
 * 已实现 Skill：
 * 1. 星座运势查询 — 关键词: "运势"、"今日运势"、"星座运势"、"运气"、"抽签"、"占卜"
 * 2. 穿搭顾问 — 关键词: "穿搭"、"搭配"、"搭配建议"、"衣服搭配"、"穿什么"、"穿衣"
 *    调用 Flask 穿搭顾问服务 (http://localhost:5000/api/bot/message) 获取穿搭方案
 */
@Service
public class SkillService {

    public record Skill(String name, String[] keywords) {}

    private final List<Skill> skills = new ArrayList<>();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String FASHION_API_URL = "http://localhost:5000/api/bot/message";

    private static final String[] CONSTELLATIONS = {
        "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
        "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
    };

    private static final String[] ELEMENTS = {
        "火象", "土象", "风象", "水象"
    };

    private static final String[] LUCK_DESC = {
        "势如破竹，宜主动出击", "稳扎稳打，宜守不宜攻", "柳暗花明，有意外之喜",
        "波澜不惊，适合沉淀思考", "逢凶化吉，贵人相助", "需谨慎行事，避免冲动"
    };

    private static final String[] CAREER_TIPS = {
        "专注核心任务，效率翻倍", "适合沟通协作，拓展人脉", "灵感涌现，创意工作最佳",
        "处理细节问题，查漏补缺", "适合学习充电，提升技能", "避免重大决策，多听取建议"
    };

    private static final String[] LOVE_TIPS = {
        "主动表达心意，有好消息", "适合约会，浪漫指数高", "感情稳定，多陪伴对方",
        "保持耐心，避免口角", "桃花旺盛，留意身边人", "单身者宜扩大社交圈"
    };

    private static final String[] HEALTH_TIPS = {
        "精力充沛，适合运动", "注意休息，避免熬夜", "饮食清淡，少油少辣",
        "关注情绪健康，冥想放松", "天气变化，注意保暖", "适度锻炼，不宜过劳"
    };

    public SkillService() {
        skills.add(new Skill("星座运势",
            new String[]{"运势", "今日运势", "星座运势", "运气", "抽签", "占卜"}));
        skills.add(new Skill("穿搭顾问",
            new String[]{"穿搭", "搭配", "搭配建议", "衣服搭配", "穿什么", "穿衣", " outfits", "服装推荐"}));
    }

    /**
     * 尝试匹配 Skill 关键词
     * @return 匹配则返回执行结果，不匹配返回 null
     */
    public String tryMatch(String message) {
        if (message == null || message.isBlank()) return null;

        String text = message.trim();
        for (Skill skill : skills) {
            for (String keyword : skill.keywords()) {
                if (text.contains(keyword)) {
                    System.out.println("🎯 [Skill] 命中 \"" + skill.name() + "\" (关键词: " + keyword + ")");
                    return switch (skill.name()) {
                        case "星座运势" -> executeZodiacFortune(text);
                        case "穿搭顾问" -> executeFashionAdvisor(text);
                        default -> null;
                    };
                }
            }
        }
        return null;
    }

    /**
     * 执行穿搭顾问查询
     * 调用 Flask 穿搭顾问服务 (http://localhost:5000/api/bot/message)
     * 将用户的穿搭需求转发给 Flask 后端，返回穿搭方案文本
     */
    private String executeFashionAdvisor(String message) {
        try {
            String json = "{\"wx_user_id\":\"java_clawbot\",\"text\":\"" +
                    message.replace("\"", "\\\"").replace("\n", "\\n") + "\"}";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FASHION_API_URL))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                String body = response.body();
                int replyIdx = body.indexOf("\"reply\":\"");
                if (replyIdx >= 0) {
                    int start = replyIdx + 9;
                    int end = start;
                    boolean escape = false;
                    for (int i = start; i < body.length(); i++) {
                        char c = body.charAt(i);
                        if (escape) { escape = false; continue; }
                        if (c == '\\') { escape = true; continue; }
                        if (c == '"') { end = i; break; }
                    }
                    String reply = body.substring(start, end)
                            .replace("\\n", "\n")
                            .replace("\\\"", "\"")
                            .replace("\\\\", "\\");
                    return "[TEXT]" + reply;
                }
                return "[TEXT]穿搭顾问服务返回格式异常，请稍后再试。";
            } else {
                System.err.println("❌ [穿搭顾问] HTTP " + response.statusCode());
                return "[TEXT]穿搭顾问服务暂时不可用 (HTTP " + response.statusCode() + ")，请确认 Flask 服务已启动。";
            }
        } catch (Exception e) {
            System.err.println("❌ [穿搭顾问] 调用失败: " + e.getMessage());
            return "[TEXT]穿搭顾问服务连接失败，请确认 Flask 服务 (localhost:5000) 已启动。";
        }
    }

    /**
     * 执行星座运势查询
     * 从消息中提取星座名，未提取到则根据日期推断当日星座
     */
    private String executeZodiacFortune(String message) {
        String constellation = extractConstellation(message);
        if (constellation == null) {
            constellation = getConstellationByDate(LocalDate.now());
        }

        String element = getElement(constellation);
        int seed = (LocalDate.now().getDayOfYear() + constellation.hashCode()) & 0x7fffffff;
        Random rng = new Random(seed);

        int overallIdx = rng.nextInt(LUCK_DESC.length);
        int careerIdx = rng.nextInt(CAREER_TIPS.length);
        int loveIdx = rng.nextInt(LOVE_TIPS.length);
        int healthIdx = rng.nextInt(HEALTH_TIPS.length);
        int luckyNumber = rng.nextInt(9) + 1;
        int luckyScore = rng.nextInt(5) + 5; // 5~9

        String[] colors = {"红色", "橙色", "黄色", "绿色", "蓝色", "紫色", "粉色", "金色"};
        String luckyColor = colors[rng.nextInt(colors.length)];

        StringBuilder sb = new StringBuilder();
        sb.append("═══════════════════════\n");
        sb.append("  ✨ ").append(constellation).append(" 今日运势 ✨\n");
        sb.append("  (").append(element).append("星座)\n");
        sb.append("═══════════════════════\n\n");
        sb.append("⭐ 综合运势：").append(luckyScore).append("/10\n");
        sb.append("    ").append(LUCK_DESC[overallIdx]).append("\n\n");
        sb.append("💼 事业运：").append(CAREER_TIPS[careerIdx]).append("\n");
        sb.append("❤️ 感情运：").append(LOVE_TIPS[loveIdx]).append("\n");
        sb.append("🏃 健康运：").append(HEALTH_TIPS[healthIdx]).append("\n\n");
        sb.append("🔢 幸运数字：").append(luckyNumber).append("\n");
        sb.append("🎨 幸运颜色：").append(luckyColor).append("\n");
        sb.append("═══════════════════════\n");
        sb.append("(运势仅供娱乐，请理性看待~)");

        return sb.toString();
    }

    private String extractConstellation(String message) {
        for (String c : CONSTELLATIONS) {
            if (message.contains(c)) return c;
        }
        return null;
    }

    private String getElement(String constellation) {
        switch (constellation) {
            case "白羊座": case "狮子座": case "射手座": return "火象";
            case "金牛座": case "处女座": case "摩羯座": return "土象";
            case "双子座": case "天秤座": case "水瓶座": return "风象";
            case "巨蟹座": case "天蝎座": case "双鱼座": return "水象";
            default: return "未知";
        }
    }

    private String getConstellationByDate(LocalDate date) {
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        if ((month == 3 && day >= 21) || (month == 4 && day <= 19)) return "白羊座";
        if ((month == 4 && day >= 20) || (month == 5 && day <= 20)) return "金牛座";
        if ((month == 5 && day >= 21) || (month == 6 && day <= 21)) return "双子座";
        if ((month == 6 && day >= 22) || (month == 7 && day <= 22)) return "巨蟹座";
        if ((month == 7 && day >= 23) || (month == 8 && day <= 22)) return "狮子座";
        if ((month == 8 && day >= 23) || (month == 9 && day <= 22)) return "处女座";
        if ((month == 9 && day >= 23) || (month == 10 && day <= 23)) return "天秤座";
        if ((month == 10 && day >= 24) || (month == 11 && day <= 21)) return "天蝎座";
        if ((month == 11 && day >= 22) || (month == 12 && day <= 21)) return "射手座";
        if ((month == 12 && day >= 22) || (month == 1 && day <= 19)) return "摩羯座";
        if ((month == 1 && day >= 20) || (month == 2 && day <= 18)) return "水瓶座";
        return "双鱼座";
    }
}
