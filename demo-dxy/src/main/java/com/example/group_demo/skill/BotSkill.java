package com.example.group_demo.skill;

import java.util.List;

/**
 * 关键词触发的技能接口。
 * 与 BotTool 不同，Skill 不依赖 LLM 的 Function Calling，
 * 而是通过关键词匹配直接执行，零 Token 消耗、零延迟。
 */
public interface BotSkill {

    String name();

    String description();

    List<String> keywords();

    String execute(String userId, String userText);
}
