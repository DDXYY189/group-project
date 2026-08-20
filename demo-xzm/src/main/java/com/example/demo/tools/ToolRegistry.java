package com.example.demo.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册中心：管理所有 BotTool 实例。
 *
 * 职责：
 * 1. 自动收集所有 BotTool 实现（Spring 注入）
 * 2. 生成 tools JSON 数组（发送给 LLM 的工具描述）
 * 3. 按 name 查找工具并执行
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, BotTool> tools = new HashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRegistry(List<BotTool> toolList) {
        for (BotTool tool : toolList) {
            tools.put(tool.getName(), tool);
            log.info("注册工具: {} → {}", tool.getName(), tool.getClass().getSimpleName());
        }
    }

    /**
     * 生成 OpenAI Function Calling 格式的 tools 数组。
     * 每个 tool 格式：{ "type":"function", "function":{ name, description, parameters } }
     */
    public List<Map<String, Object>> getToolsSchema() {
        return tools.values().stream()
                .map(tool -> Map.of(
                        "type", "function",
                        "function", Map.of(
                                "name", tool.getName(),
                                "description", tool.getDescription(),
                                "parameters", tool.getParameters()
                        )
                ))
                .collect(Collectors.toList());
    }

    /**
     * 按工具名查找并执行工具。
     *
     * @param toolName  LLM 返回的 function.name
     * @param arguments LLM 返回的 function.arguments（JSON 字符串）
     * @return 工具执行结果文本
     */
    public String executeTool(String toolName, String arguments) {
        BotTool tool = tools.get(toolName);
        if (tool == null) {
            log.warn("未找到工具: {}", toolName);
            return "工具 " + toolName + " 不存在";
        }

        try {
            Map<String, Object> args = Map.of();
            if (arguments != null && !arguments.isBlank()) {
                args = objectMapper.readValue(arguments, Map.class);
            }
            log.info("执行工具: {} args={}", toolName, args);
            String result = tool.execute(args);
            log.info("工具 {} 返回: {}", toolName, result);
            return result;
        } catch (Exception e) {
            log.error("工具执行失败 {}: {}", toolName, e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    public boolean hasTools() {
        return !tools.isEmpty();
    }
}
