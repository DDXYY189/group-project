package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class CurrentTimeTool implements BotTool {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前日期、时间和星期几，返回中文文本。当用户询问现在几点、今天日期或星期几时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        return now.format(FORMATTER) + " " + WEEKDAYS[now.getDayOfWeek().getValue() - 1];
    }
}
