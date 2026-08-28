package com.example.group_demo.skill.travel;

import com.example.group_demo.skill.Skill;
import com.example.group_demo.travel.TravelAgentService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 旅行规划长任务技能：命中后交给 TravelAgentService 自主执行整条链路，
 * 最终返回文本方案、网页链接、待办清单和语音摘要。
 */
@Component
public class TravelSkill implements Skill {

    private final TravelAgentService travelAgentService;

    public TravelSkill(TravelAgentService travelAgentService) {
        this.travelAgentService = travelAgentService;
    }

    @Override
    public String name() {
        return "travel_planner";
    }

    @Override
    public String description() {
        return "长任务旅行规划 Agent：解析一句话目标，自动查天气、联网搜索、检索知识库、"
            + "生成逐日行程、渲染网页、写入待办并生成语音摘要。";
    }

    @Override
    public List<String> keywords() {
        return List.of(
            "旅游", "旅行", "攻略", "行程", "出去玩", "周末去哪", "去哪玩",
            "度假", "景点", "出行", "旅游计划", "旅行计划", "日游", "游玩", "出游"
        );
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public boolean directReply() {
        return true;
    }

    @Override
    public String execute(String userId, String text) {
        return travelAgentService.run(userId, text).reply();
    }
}
