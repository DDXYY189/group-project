package com.example.demo_wkx.skill;

import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class SkillService {

    public record Skill(String name, String[] keywords) {}

    private final List<Skill> skills = new ArrayList<>();

    private static final String[] CONSTELLATIONS = {
        "白羊座", "金牛座", "双子座", "巨蟹座", "狮子座", "处女座",
        "天秤座", "天蝎座", "射手座", "摩羯座", "水瓶座", "双鱼座"
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
    }

    public String tryMatch(String message) {
        if (message == null || message.isBlank()) return null;
        String text = message.trim();
        for (Skill skill : skills) {
            for (String keyword : skill.keywords()) {
                if (text.contains(keyword)) {
                    System.out.println("🎯 [Skill] 命中 \"" + skill.name() + "\" (关键词: " + keyword + ")");
                    return executeZodiacFortune(text);
                }
            }
        }
        return null;
    }

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
        int luckyScore = rng.nextInt(5) + 5;
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
