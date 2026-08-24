package com.example.group_demo.skill;

import java.util.List;

/**
 * 自定义技能：一组可复用的领域能力，由关键词触发。
 *
 * <p>两种执行模式：
 * <ul>
 *   <li>直接执行：{@code directReply()==true}，命中后调用 {@link #execute(String, String)} 返回结果；</li>
 *   <li>LLM 驱动：命中后把 {@link #instructions()} 注入系统提示词，并只开放
 *       {@link #allowedTools()} 中声明的工具。</li>
 * </ul>
 */
public interface Skill {

    String name();

    String description();

    List<String> keywords();

    /**
     * 匹配优先级，数值越大越优先。默认 0。
     */
    default int priority() {
        return 0;
    }

    default boolean directReply() {
        return false;
    }

    /**
     * LLM 驱动模式下注入系统提示词的技能手册。
     */
    default String instructions() {
        return "";
    }

    /**
     * LLM 驱动模式下允许调用的工具名；不重写时技能不带工具。
     */
    default List<String> allowedTools() {
        return List.of();
    }

    /**
     * 直接执行模式的结果。
     */
    default String execute(String userId, String text) {
        throw new UnsupportedOperationException("Skill " + name() + " 未实现直接执行逻辑");
    }

    default boolean matches(String text) {
        if (text == null) {
            return false;
        }
        return keywords().stream()
            .anyMatch(keyword -> !keyword.isBlank() && text.contains(keyword));
    }
}
