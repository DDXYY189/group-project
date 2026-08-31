package com.example.group_demo.scheduler;

import com.example.group_demo.tool.BotTool;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class ReminderTool implements BotTool {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

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
            + "当用户要求到点提醒、定时提醒、每天提醒、设置闹钟时调用（例如“晚上7点开会”“明天早上9点提醒我”“每天8点打卡”）。"
            + "一次性提醒请提供 fire_at（yyyy-MM-dd HH:mm）或 time_text（自然语言时间，如“晚上7点”）。";
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
                    "description", "一次性提醒触发时间，格式 yyyy-MM-dd HH:mm 或 ISO 时间，例如 2026-08-30 19:00"
                ),
                "time_text", Map.of(
                    "type", "string",
                    "description", "自然语言触发时间，例如：晚上7点、明天早上9点、后天下午3点、下周一8点"
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
        String content = arguments.path("content").asText("");
        String time = arguments.path("time").asText("");
        String cron = arguments.path("cron").asText("");
        String timeText = arguments.path("time_text").asText("");
        ReminderService.Reminder reminder;
        if ("once".equals(scheduleType)) {
            Long fireAt = resolveFireAt(arguments);
            if (fireAt == null) {
                throw new IllegalArgumentException(
                    "一次性提醒需要触发时间，请提供 fire_at（如 2026-08-30 19:00）或 time_text（如 晚上7点）");
            }
            reminder = reminderService.add(userId, content, scheduleType, null, null, fireAt);
        } else {
            if ("daily".equals(scheduleType) && time.isBlank() && !timeText.isBlank()) {
                LocalTime parsed = ReminderTimeParser.parseTimeText(timeText);
                if (parsed != null) {
                    time = parsed.format(HH_MM);
                }
            }
            reminder = reminderService.add(
                userId,
                content,
                scheduleType,
                time.isBlank() ? null : time,
                cron.isBlank() ? null : cron,
                null
            );
        }
        return "已创建定时提醒 " + reminderService.describe(reminder);
    }

    private Long resolveFireAt(JsonNode arguments) {
        String fireAt = arguments.path("fire_at").asText("");
        String timeText = arguments.path("time_text").asText("");
        String time = arguments.path("time").asText("");
        long now = System.currentTimeMillis();
        if (!fireAt.isBlank()) {
            return ReminderTimeParser.parse(fireAt, now, ZONE);
        }
        String candidate = !timeText.isBlank() ? timeText : time;
        return candidate.isBlank() ? null : ReminderTimeParser.parse(candidate, now, ZONE);
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
