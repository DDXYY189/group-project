package com.example.group_demo.skill;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SkillRegistry {

    private final Map<String, BotSkill> skills = new LinkedHashMap<>();

    public SkillRegistry(List<BotSkill> skillList) {
        for (BotSkill skill : skillList) {
            if (skills.put(skill.name(), skill) != null) {
                throw new IllegalStateException("重复的技能名: " + skill.name());
            }
        }
    }

    public Optional<BotSkill> match(String userText) {
        if (userText == null || userText.isBlank()) {
            return Optional.empty();
        }
        for (BotSkill skill : skills.values()) {
            for (String keyword : skill.keywords()) {
                if (userText.contains(keyword)) {
                    return Optional.of(skill);
                }
            }
        }
        return Optional.empty();
    }

    public List<String> names() {
        return List.copyOf(skills.keySet());
    }
}
