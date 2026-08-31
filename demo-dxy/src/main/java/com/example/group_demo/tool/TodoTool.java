package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class TodoTool implements BotTool {

    private final TodoService todoService;

    public TodoTool(TodoService todoService) {
        this.todoService = todoService;
    }

    @Override
    public String name() {
        return "manage_todo";
    }

    @Override
    public String description() {
        return "管理当前用户的待办事项：add 添加、list 查看、done 完成。"
            + "当用户要记录不设时间提醒的待办清单（如“买牛奶”“写作业”）时调用；需要到点/定时提醒请使用 manage_reminder。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("add", "list", "done"),
                    "description", "操作类型"
                ),
                "text", Map.of(
                    "type", "string",
                    "description", "待办内容，action 为 add 时填写"
                ),
                "id", Map.of(
                    "type", "integer",
                    "description", "待办编号，action 为 done 时填写"
                )
            ),
            "required", List.of("action"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String action = arguments.path("action").asText("").trim();
        return switch (action) {
            case "add" -> todoService.add(userId, arguments.path("text").asText(""));
            case "list" -> todoService.list(userId);
            case "done" -> todoService.done(userId, arguments.path("id").asInt(-1));
            default -> throw new IllegalArgumentException("不支持的 action: " + action);
        };
    }
}
