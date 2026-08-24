package com.example.group_demo.skill;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    private BotSkill jokeSkill() {
        return new BotSkill() {
            @Override
            public String name() { return "joke"; }

            @Override
            public String description() { return "讲笑话"; }

            @Override
            public List<String> keywords() { return List.of("笑话", "讲个笑话"); }

            @Override
            public String execute(String userId, String userText) { return "这是一个笑话"; }
        };
    }

    @Test
    void matchesByKeyword() {
        SkillRegistry registry = new SkillRegistry(List.of(jokeSkill()));
        Optional<BotSkill> matched = registry.match("讲个笑话");
        assertTrue(matched.isPresent());
        assertEquals("joke", matched.get().name());
    }

    @Test
    void noMatchReturnsEmpty() {
        SkillRegistry registry = new SkillRegistry(List.of(jokeSkill()));
        Optional<BotSkill> matched = registry.match("今天天气怎么样");
        assertTrue(matched.isEmpty());
    }

    @Test
    void emptyTextReturnsEmpty() {
        SkillRegistry registry = new SkillRegistry(List.of(jokeSkill()));
        assertTrue(registry.match("").isEmpty());
        assertTrue(registry.match(null).isEmpty());
    }

    @Test
    void rejectsDuplicateNames() {
        assertThrows(IllegalStateException.class,
            () -> new SkillRegistry(List.of(jokeSkill(), jokeSkill())));
    }

    @Test
    void namesListsAllSkills() {
        SkillRegistry registry = new SkillRegistry(List.of(jokeSkill()));
        assertEquals(List.of("joke"), registry.names());
    }
}
