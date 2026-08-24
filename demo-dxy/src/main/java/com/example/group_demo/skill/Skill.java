package com.example.group_demo.skill;

/**
 * 关键词触发的 Skill 接口。
 * 与 BotTool（LLM function calling）不同，Skill 通过关键词匹配直接触发，
 * 命中后立即执行并返回结果，不再走 LLM 流程。
 */
public interface Skill {

    /**
     * Skill 名称，用于日志和识别。
     */
    String name();

    /**
     * 触发关键词列表，用户输入包含任意一个关键词即命中。
     */
    String[] keywords();

    /**
     * 执行 Skill 逻辑，返回直接回复给用户的文本。
     *
     * @param userId   用户ID
     * @param userText 用户原始输入文本
     * @return 回复给用户的文本
     */
    String execute(String userId, String userText);
}
