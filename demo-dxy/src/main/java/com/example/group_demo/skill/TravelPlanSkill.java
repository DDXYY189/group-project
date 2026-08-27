package com.example.group_demo.skill;

import com.example.group_demo.agent.TravelPlannerAgent;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TravelPlanSkill implements BotSkill {

    private final TravelPlannerAgent travelPlannerAgent;

    public TravelPlanSkill(TravelPlannerAgent travelPlannerAgent) {
        this.travelPlannerAgent = travelPlannerAgent;
    }

    @Override
    public String name() {
        return "travel_plan";
    }

    @Override
    public String description() {
        return "一键生成完整旅游方案（含天气、车票、酒店、美食、行程、注意事项）";
    }

    @Override
    public List<String> keywords() {
        return List.of(
            "旅游方案", "旅行方案", "旅游攻略", "旅行攻略",
            "旅游规划", "旅行规划", "做一份旅游", "做一份旅行",
            "帮我规划旅游", "帮我规划旅行", "旅游计划", "旅行计划",
            "日游方案", "日游攻略", "日游计划"
        );
    }

    @Override
    public String execute(String userId, String userText) {
        return travelPlannerAgent.plan(userId, userText);
    }
}
