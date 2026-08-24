package com.example.group_demo.skill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Skill 体系测试：SkillRegistry 关键词匹配 + CountdownSkill 倒计时计算。
 */
class SkillRegistryTest {

    @Test
    @DisplayName("SkillRegistry 关键词匹配命中")
    void matchHit() {
        CountdownSkill skill = new CountdownSkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));

        Skill matched = registry.match("距离12月4日还有多久");
        assertNotNull(matched, "包含'距离'关键词应命中");
        assertEquals("countdown", matched.name());
        System.out.println("【Skill命中】skill=" + matched.name());
    }

    @Test
    @DisplayName("SkillRegistry 无匹配返回 null")
    void matchMiss() {
        CountdownSkill skill = new CountdownSkill();
        SkillRegistry registry = new SkillRegistry(List.of(skill));

        Skill matched = registry.match("今天天气怎么样");
        assertNull(matched, "不包含任何关键词时应返回 null");
        System.out.println("【Skill未命中】返回null，进入下一步RAG");
    }

    @Test
    @DisplayName("CountdownSkill 倒计时计算")
    void countdownExecute() {
        CountdownSkill skill = new CountdownSkill();
        String result = skill.execute("user1", "距离12月4日还有多久");
        assertNotNull(result);
        assertTrue(result.contains("12月04日"), "应包含目标日期");
        assertTrue(result.contains("天"), "应包含天数");
        System.out.println("【倒计时结果】" + result);
    }

    @Test
    @DisplayName("CountdownSkill 无法识别日期 → 提示用户")
    void countdownNoDate() {
        CountdownSkill skill = new CountdownSkill();
        String result = skill.execute("user1", "倒计时");
        assertTrue(result.contains("请告诉我"), "无法识别日期时应返回提示");
        System.out.println("【无日期提示】" + result);
    }
}
