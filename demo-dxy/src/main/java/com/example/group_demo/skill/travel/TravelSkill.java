package com.example.group_demo.skill.travel;

import com.example.group_demo.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 旅行规划技能：由 LLM 驱动，只开放搜索、天气、待办三个工具，
 * 按技能手册完成攻略查询、天气提醒和行程写入待办。
 */
@Component
public class TravelSkill implements Skill {

    @Override
    public String name() {
        return "travel_planner";
    }

    @Override
    public String description() {
        return "旅行规划助手，根据目的地、天数和偏好生成逐日行程，并把必做事项写入待办。";
    }

    @Override
    public List<String> keywords() {
        return List.of(
            "旅游", "旅行", "攻略", "行程", "出去玩", "周末去哪", "去哪玩",
            "度假", "景点", "出行", "旅游计划", "旅行计划"
        );
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public List<String> allowedTools() {
        return List.of("web_search", "query_weather", "manage_todo");
    }

    @Override
    public String instructions() {
        return "你是旅行规划助手。当用户想规划旅行时，按以下流程执行：\n"
            + "1. 先确认目的地、出行天数、预算和同行人；信息缺失时简要询问，不要编造。\n"
            + "2. 使用 web_search 搜索目的地攻略、景点、美食、交通和住宿信息，必须以搜索结果为准。\n"
            + "3. 使用 query_weather 查询目的地近期天气，提醒用户准备相应衣物。\n"
            + "4. 把行程按天整理成清晰列表，包含景点、时间、交通和注意事项；"
            + "再用 manage_todo 的 add 动作把每天必做事项写入待办。\n"
            + "5. 最后给出逐日行程、必备物品、预算建议和注意事项。\n"
            + "如果搜索或天气获取失败，明确说明该部分暂缺，不得虚构信息。";
    }
}
