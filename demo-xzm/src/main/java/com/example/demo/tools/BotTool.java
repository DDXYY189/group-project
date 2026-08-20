package com.example.demo.tools;

import java.util.List;
import java.util.Map;

/**
 * Bot 工具接口：每个自定义工具实现此接口，由 ToolRegistry 统一管理。
 *
 * Function Calling 流程：
 * 1. LLM 收到用户消息 + tools 列表（含 JSON Schema）
 * 2. LLM 决定调用某个工具，返回 tool_calls（name + arguments）
 * 3. 代码通过 tool name 找到对应 BotTool，调用 execute(arguments)
 * 4. 将工具返回内容送回 LLM，LLM 生成最终自然语言回复
 */
public interface BotTool {

    /** 工具函数名（LLM 返回的 tool_calls.function.name 对应此值） */
    String getName();

    /** 工具描述（告诉 LLM 何时该调用此工具） */
    String getDescription();

    /**
     * JSON Schema 参数定义，格式：
     * {
     *   "type": "object",
     *   "properties": { ... },
     *   "required": [...]
     * }
     */
    Map<String, Object> getParameters();

    /**
     * 执行工具，返回文本结果送回 LLM。
     *
     * @param arguments LLM 生成的参数（已从 JSON 解析为 Map）
     * @return 工具执行结果文本
     */
    String execute(Map<String, Object> arguments);
}
