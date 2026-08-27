package com.example.group_demo.skill.plan;

import com.example.group_demo.agent.PlanAgentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlanSkillTest {

    private PlanAgentService agent() {
        return new PlanAgentService(null, null) {
            @Override
            public String run(String userId, String goal) {
                return "agent:" + goal;
            }
        };
    }

    @Test
    void matchesPlanningKeywords() {
        PlanSkill skill = new PlanSkill(agent());
        assertTrue(skill.matches("帮我制定下周学习计划"));
        assertTrue(skill.matches("给我一份周计划"));
        assertTrue(skill.matches("规划一下"));
        assertFalse(skill.matches("今天天气怎么样"));
    }

    @Test
    void isDirectReplyAndDelegatesToAgent() {
        PlanSkill skill = new PlanSkill(agent());
        assertTrue(skill.directReply());
        assertEquals("weekly_plan_agent", skill.name());
        assertEquals("agent:帮我制定周计划", skill.execute("u1", "帮我制定周计划"));
    }
}
