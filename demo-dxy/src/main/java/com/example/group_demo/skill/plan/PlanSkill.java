package com.example.group_demo.skill.plan;

import com.example.group_demo.agent.PlanAgentService;
import com.example.group_demo.skill.Skill;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 周计划长任务技能：命中"制定计划/周计划/下周安排"等关键词时，
 * 直接交给 PlanAgentService 执行拆解-执行-整合闭环。
 */
@Component
public class PlanSkill implements Skill {

    private final PlanAgentService planAgentService;

    public PlanSkill(PlanAgentService planAgentService) {
        this.planAgentService = planAgentService;
    }

    @Override
    public String name() {
        return "weekly_plan_agent";
    }

    @Override
    public String description() {
        return "周计划长任务 Agent：把一句话目标拆解为子任务，检索知识库并逐步执行，"
            + "最终整合成一份完整的一周学习生活安排。";
    }

    @Override
    public List<String> keywords() {
        return List.of("制定计划", "学习计划", "周计划", "一周安排", "下周安排");
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
        return planAgentService.run(userId, text);
    }
}
