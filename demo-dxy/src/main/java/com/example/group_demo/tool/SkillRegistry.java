package com.example.group_demo.tool;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Skill 注册表：Spring 自动收集所有 @Service 的 BotSkill 实现，
 * 提供关键词匹配与执行能力。
 */
@Service
public class SkillRegistry {

    private final List<BotSkill> skills;

    public SkillRegistry(List<BotSkill> skills) {
        this.skills = skills != null ? skills : List.of();
    }

    /**
     * 匹配消息文本，返回第一个关键词命中的 Skill。
     */
    public Optional<BotSkill> match(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (BotSkill skill : skills) {
            for (String keyword : skill.keywords()) {
                if (text.contains(keyword)) {
                    return Optional.of(skill);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 匹配并执行 Skill；未命中返回 null，由上层继续走 RAG / LLM。
     */
    public String execute(String userId, String text) {
        Optional<BotSkill> matched = match(text);
        if (matched.isEmpty()) {
            return null;
        }
        return matched.get().execute(userId, text);
    }

    public List<String> names() {
        return skills.stream().map(BotSkill::name).toList();
    }
}
