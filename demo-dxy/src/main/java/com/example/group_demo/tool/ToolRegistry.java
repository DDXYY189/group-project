package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            if (tools.put(tool.name(), tool) != null) {
                throw new IllegalStateException("重复的工具名: " + tool.name());
            }
        }
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
}
