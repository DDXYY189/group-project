package com.example.group_demo.tool;

import java.util.List;

/**
 * 微信机器人的 Skill：关键词直接路由，命中即执行，不走 LLM 判断。
 * 与 BotTool 的区别：
 *  - BotTool 由 LLM 推理决定是否调用，参数是 JSON（JsonNode）
 *  - BotSkill 由关键词直接匹配触发，参数是原始消息文本
 */
public interface BotSkill {

    /**
     * Skill 名称，用于日志和排障。
     */
    String name();

    /**
     * 触发关键词列表，消息文本包含任一关键词即命中。
     */
    List<String> keywords();

    /**
     * 直接处理消息并返回回复文本。
     *
     * @param userId 发送消息的用户
     * @param text   原始消息文本
     * @return 要回复给用户的内容
     */
    String execute(String userId, String text);
}
