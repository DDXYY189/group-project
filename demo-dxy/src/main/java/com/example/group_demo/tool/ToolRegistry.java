package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, BotTool> tools = new LinkedHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ToolRegistry(List<BotTool> toolList) {
        if (toolList == null) {
            return;
        }
        for (BotTool tool : toolList) {
            register(tool);
        }
    }

    public void register(BotTool tool) {
        if (tool == null) {
            return;
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalStateException("重复的工具名: " + tool.name());
        }
        tools.put(tool.name(), tool);
    }

    public List<BotTool> all() {
        return List.copyOf(tools.values());
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    public BotTool find(String name) {
        return tools.get(name);
    }

    public List<Map<String, Object>> jsonSchemas() {
        return all().stream().map(BotTool::jsonSchema).toList();
    }

    public String execute(String userId, String name, String argumentsJson) {
        BotTool tool = tools.get(name);
        if (tool == null) {
            return "工具不存在: " + name;
        }
        try {
            JsonNode arguments = objectMapper.readTree(argumentsJson);
            String result = tool.execute(userId, arguments);
            log.info("工具执行成功 userId={} tool={} arguments={} result={}",
                userId, name, argumentsJson, result);
            return result;
        } catch (Exception e) {
            log.warn("工具执行失败 userId={} tool={} arguments={}", userId, name, argumentsJson, e);
            return "工具调用失败: " + e.getMessage();
        }
    }

    /**
     * 链式编排使用：工具不存在或执行异常时直接抛出，由调用方决定是否中断整条链。
     */
    public String executeStrict(String userId, String name, String argumentsJson) {
        BotTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("工具不存在: " + name);
        }
        JsonNode arguments;
        try {
            arguments = objectMapper.readTree(argumentsJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("工具参数不是合法 JSON: " + argumentsJson, e);
        }
        return tool.execute(userId, arguments);
    }
}
