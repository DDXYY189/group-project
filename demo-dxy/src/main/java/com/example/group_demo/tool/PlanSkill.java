package com.example.group_demo.tool;

import com.example.group_demo.agent.PlanAgentService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 周计划 Skill：命中"制定计划/周计划/下周安排"等关键词时，
 * 直接交给 PlanAgentService 执行长任务（拆解 → 执行 → 整合）。
 */
@Service
public class PlanSkill implements BotSkill {

    private final PlanAgentService planAgentService;

    public PlanSkill(PlanAgentService planAgentService) {
        this.planAgentService = planAgentService;
    }

    @Override
    public String name() {
        return "plan_skill";
    }

    @Override
    public List<String> keywords() {
        return List.of("制定计划", "学习计划", "周计划", "一周安排", "下周安排", "规划一下", "帮我规划");
    }

    @Override
    public String execute(String userId, String text) {
        return planAgentService.run(userId, text);
    }
}
