package com.example.group_demo.scheduler;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ReminderTool implements BotTool {

    private final ReminderService reminderService;

    public ReminderTool(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @Override
    public String name() {
        return "manage_reminder";
    }

    @Override
    public String description() {
        return "管理当前用户的定时提醒：add 创建一次性/每天/Cron 提醒，list 查看提醒，delete 删除提醒。"
            + "当用户要求定时提醒、到点提醒、每天提醒、设置闹钟时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "action", Map.of(
                    "type", "string",
                    "enum", List.of("add", "list", "delete"),
                    "description", "操作类型"
                ),
                "content", Map.of(
                    "type", "string",
                    "description", "提醒内容，action 为 add 时填写"
                ),
                "schedule_type", Map.of(
                    "type", "string",
                    "enum", List.of("once", "daily", "cron"),
                    "description", "once 一次性，daily 每天，cron 自定义表达式"
                ),
                "fire_at", Map.of(
                    "type", "string",
                    "description", "一次性提醒触发时间，格式 yyyy-MM-dd HH:mm 或 ISO 时间"
                ),
                "time", Map.of(
                    "type", "string",
                    "description", "每天提醒时间，格式 HH:mm"
                ),
                "cron", Map.of(
                    "type", "string",
                    "description", "Cron 表达式，例如 0 0 9 * * *"
                ),
                "id", Map.of(
                    "type", "integer",
                    "description", "提醒编号，action 为 delete 时填写"
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
            case "add" -> add(userId, arguments);
            case "list" -> list(userId);
            case "delete" -> reminderService.remove(userId, arguments.path("id").asLong(-1));
            default -> throw new IllegalArgumentException("不支持的 action: " + action);
        };
    }

    private String add(String userId, JsonNode arguments) {
        String scheduleType = arguments.path("schedule_type").asText("once");
        String time = arguments.path("time").asText("");
        String cron = arguments.path("cron").asText("");
        Long fireAt = ReminderTimeParser.parse(arguments.path("fire_at").asText(""));
        ReminderService.Reminder reminder = reminderService.add(
            userId,
            arguments.path("content").asText(""),
            scheduleType,
            time.isBlank() ? null : time,
            cron.isBlank() ? null : cron,
            fireAt
        );
        return "已创建定时提醒 " + reminderService.describe(reminder);
    }

    private String list(String userId) {
        List<ReminderService.Reminder> reminders = reminderService.list(userId);
        if (reminders.isEmpty()) {
            return "暂无定时提醒";
        }
        List<String> lines = reminders.stream().map(reminderService::describe).toList();
        return "你的定时提醒：\n" + String.join("\n", lines);
    }
}
