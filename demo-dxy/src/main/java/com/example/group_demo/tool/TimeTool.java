package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Service
public class TimeTool implements BotTool {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss");
    private static final String[] WEEKDAYS = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};

    @Override
    public String name() {
        return "get_current_time";
    }

    @Override
    public String description() {
        return "获取当前的日期、时间和星期信息。当用户询问现在几点、今天日期、星期几、现在是什么时候等问题时调用此工具。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(),
            "required", List.of()
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String dateTime = now.format(FORMATTER);
        String weekDay = WEEKDAYS[now.getDayOfWeek().getValue() - 1];
        return "当前时间：" + dateTime + " " + weekDay + "（北京时间）";
    }
}
