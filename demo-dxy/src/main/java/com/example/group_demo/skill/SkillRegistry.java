package com.example.group_demo.skill;

import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 技能注册表：启动时自动收集所有 Skill Bean，按关键词匹配用户消息。
 * 新增技能只需新增一个实现类，无需修改本类。
 */
@Service
public class SkillRegistry {

    private final List<Skill> skills;

    public SkillRegistry(List<Skill> skillList) {
        this.skills = (skillList == null ? List.<Skill>of() : skillList).stream()
            .sorted(Comparator.comparingInt(Skill::priority).reversed()
                .thenComparing(Skill::name))
            .toList();
    }

    public Skill match(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        for (Skill skill : skills) {
            if (skill.matches(text)) {
                return skill;
            }
        }
        return null;
    }

    public List<Skill> all() {
        return skills;
    }

    public List<Map<String, Object>> summaries() {
        return skills.stream().map(skill -> Map.of(
            "name", skill.name(),
            "description", skill.description(),
            "keywords", skill.keywords(),
            "directReply", skill.directReply(),
            "tools", skill.allowedTools()
        )).toList();
    }
}
