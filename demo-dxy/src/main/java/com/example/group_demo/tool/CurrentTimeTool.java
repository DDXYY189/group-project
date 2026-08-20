package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.nlf.calendar.Solar;
import com.nlf.calendar.Lunar;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 时间查询工具：返回公历日期时间、星期、农历日期（天干地支年、生肖）、节气。
 */
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
        return "获取当前日期、时间、星期、农历日期（天干地支年、生肖）、节气等信息。"
                + "当用户询问现在几点、今天日期、农历日期、星期几时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", List.of());
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        LocalDateTime now = LocalDateTime.now(ZONE);
        String gregorian = now.format(FORMATTER);
        String weekday = WEEKDAYS[now.getDayOfWeek().getValue() - 1];

        // 计算农历
        Date date = Date.from(now.atZone(ZONE).toInstant());
        Solar solar = Solar.fromDate(date);
        Lunar lunar = solar.getLunar();
        String lunarDate = lunar.getYearInGanZhi() + "年"
                + lunar.getMonthInChinese() + "月"
                + lunar.getDayInChinese()
                + "（" + lunar.getYearShengXiao() + "年）";
        String jieQi = lunar.getJieQi();

        StringBuilder result = new StringBuilder();
        result.append("当前北京时间：").append(gregorian).append(" ").append(weekday);
        result.append("\n农历：").append(lunarDate);
        if (jieQi != null && !jieQi.isEmpty()) {
            result.append("\n节气：").append(jieQi);
        }
        return result.toString();
    }
}
