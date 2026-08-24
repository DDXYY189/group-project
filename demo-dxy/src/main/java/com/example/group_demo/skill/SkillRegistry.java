package com.example.group_demo.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 注册中心，管理所有关键词触发的 Skill。
 */
@Service
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(List<Skill> skillList) {
        if (skillList == null) {
            return;
        }
        for (Skill skill : skillList) {
            if (skills.put(skill.name(), skill) != null) {
                throw new IllegalStateException("重复的 Skill 名称: " + skill.name());
            }
        }
        log.info("已注册 {} 个 Skill: {}", skills.size(), skills.keySet());
    }

    /**
     * 尝试匹配用户输入到某个 Skill。
     *
     * @param userText 用户输入文本
     * @return 命中的 Skill，未命中返回 null
     */
    public Skill match(String userText) {
        if (userText == null || userText.isBlank()) {
            return null;
        }
        String text = userText.trim();
        for (Skill skill : skills.values()) {
            String[] keywords = skill.keywords();
            if (keywords == null) {
                continue;
            }
            for (String keyword : keywords) {
                if (keyword != null && !keyword.isBlank() && text.contains(keyword)) {
                    log.info("Skill 匹配命中: skill={} keyword={}", skill.name(), keyword);
                    return skill;
                }
            }
        }
        return null;
    }

    public List<String> names() {
        return List.copyOf(skills.keySet());
    }
}
