package com.example.group_demo.tool;

import com.example.group_demo.llm.ConversationMemoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class MemoryTool implements BotTool {

    private final ConversationMemoryService memoryService;

    public MemoryTool(ConversationMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public String name() {
        return "clear_memory";
    }

    @Override
    public String description() {
        return "清除当前用户的长时对话记忆和摘要。当用户要求忘记之前对话、清除记忆、重新开始时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        memoryService.clear(userId);
        return "已清除你的对话记忆，我们重新开始吧。";
    }
}
