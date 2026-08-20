package com.example.demo.tools;

import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 时间查询工具：获取当前日期和时间。
 *
 * LLM 看到此工具描述后，当用户问"现在几点"、"今天星期几"时，会自动调用：
 *   get_current_time()
 * 工具返回准确时间，LLM 再用自然语言回复用户。
 */
@Component
public class TimeTool implements BotTool {

    @Override
    public String getName() {
        return "get_current_time";
    }

    @Override
    public String getDescription() {
        return "获取当前的日期、时间和星期信息。当用户询问现在几点、今天日期、星期几、现在是什么时候等问题时调用此工具。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of()
        );
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm:ss");
        String dateTime = now.format(fmt);
        String[] weekDays = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        String weekDay = weekDays[now.getDayOfWeek().getValue() - 1];
        return "当前时间：" + dateTime + " " + weekDay + "（北京时间）";
    }
}
