package com.example.group_demo.skill;

import com.example.group_demo.skill.travel.TravelSkill;
import com.example.group_demo.skill.plan.PlanSkill;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class SkillRegistryTest {

    private final TravelSkill travel = new TravelSkill(null);

    private Skill directSkill() {
        return new Skill() {
            @Override
            public String name() {
                return "echo_skill";
            }

            @Override
            public String description() {
                return "回显技能";
            }

            @Override
            public List<String> keywords() {
                return List.of("回显", "echo", "旅行");
            }

            @Override
            public boolean directReply() {
                return true;
            }

            @Override
            public String execute(String userId, String text) {
                return "回显:" + text;
            }
        };
    }

    @Test
    void matchesTravelKeywords() {
        SkillRegistry registry = new SkillRegistry(List.of(travel));
        assertSame(travel, registry.match("帮我规划一下周末去成都的行程"));
        assertSame(travel, registry.match("我想做一份旅行攻略"));
        assertSame(travel, registry.match("这周末去哪玩"));
        assertSame(travel, registry.match("预算5000，帮我规划4月1-3号上海3日游，两个人，喜欢美食和夜景"));
        assertSame(travel, registry.match("帮我规划上海3日游"));
        assertNull(registry.match("今天天气怎么样"));
    }

    @Test
    void travelPromptPrefersTravelSkillOverWeeklyPlanSkill() {
        SkillRegistry registry = new SkillRegistry(List.of(new PlanSkill(null), travel));
        assertSame(travel, registry.match("预算5000，帮我规划4月1-3号上海3日游"));
    }

    @Test
    void prefersHigherPrioritySkillWhenBothMatch() {
        SkillRegistry registry = new SkillRegistry(List.of(directSkill(), travel));
        assertSame(travel, registry.match("帮我规划一次旅行"));
    }

    @Test
    void exposesSkillSummaries() {
        SkillRegistry registry = new SkillRegistry(List.of(travel));
        assertEquals("travel_planner", registry.summaries().get(0).get("name"));
        assertEquals(List.of(),
            registry.summaries().get(0).get("tools"));
    }
}
