package com.example.group_demo.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private BotSkill testSkill() {
        return new BotSkill() {
            @Override
            public String name() { return "test_skill"; }

            @Override
            public String description() { return "测试技能"; }

            @Override
            public List<String> keywords() { return List.of("测试", "触发"); }

            @Override
            public String execute(String userId, String userText) { return "技能执行结果"; }
        };
    }

    @Test
    void matchesByKeyword() {
        SkillRegistry registry = new SkillRegistry(List.of(testSkill()));
        Optional<BotSkill> matched = registry.match("帮我测试一下");
        assertTrue(matched.isPresent());
        assertEquals("test_skill", matched.get().name());
    }

    @Test
    void noMatchReturnsEmpty() {
        SkillRegistry registry = new SkillRegistry(List.of(testSkill()));
        Optional<BotSkill> matched = registry.match("今天天气怎么样");
        assertTrue(matched.isEmpty());
    }

    @Test
    void emptyTextReturnsEmpty() {
        SkillRegistry registry = new SkillRegistry(List.of(testSkill()));
        assertTrue(registry.match("").isEmpty());
        assertTrue(registry.match(null).isEmpty());
    }

    @Test
    void rejectsDuplicateNames() {
        assertThrows(IllegalStateException.class,
            () -> new SkillRegistry(List.of(testSkill(), testSkill())));
    }

    @Test
    void namesListsAllSkills() {
        SkillRegistry registry = new SkillRegistry(List.of(testSkill()));
        assertEquals(List.of("test_skill"), registry.names());
    }
}
