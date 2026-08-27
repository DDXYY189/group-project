package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class TrainTicketTool implements BotTool {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("MM-dd");

    @Override
    public String name() {
        return "search_train_tickets";
    }

    @Override
    public String description() {
        return "查询两个城市之间的高铁/动车往返车票信息，推荐最优车次。当用户需要查询火车票、订高铁票时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "from_city", Map.of(
                    "type", "string",
                    "description", "出发城市，如：北京、上海、南京"
                ),
                "to_city", Map.of(
                    "type", "string",
                    "description", "目的城市，如：无锡、杭州、苏州"
                ),
                "depart_date", Map.of(
                    "type", "string",
                    "description", "出发日期，格式 YYYY-MM-DD，如：2026-09-01"
                ),
                "return_date", Map.of(
                    "type", "string",
                    "description", "返程日期，格式 YYYY-MM-DD，如：2026-09-03，不填则只查去程"
                ),
                "preference", Map.of(
                    "type", "string",
                    "description", "偏好：time（时间优先）、price（价格优先）、comfort（舒适优先）",
                    "enum", List.of("time", "price", "comfort")
                )
            ),
            "required", List.of("from_city", "to_city", "depart_date"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String fromCity = arguments.path("from_city").asText();
        String toCity = arguments.path("to_city").asText();
        String departDate = arguments.path("depart_date").asText();
        String returnDate = arguments.path("return_date").asText("");
        String preference = arguments.path("preference").asText("time");

        if (fromCity.isBlank() || toCity.isBlank() || departDate.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数：出发城市、到达城市、出发日期");
        }

        List<String> departTrains = generateTrains(fromCity, toCity, departDate, preference);
        StringBuilder sb = new StringBuilder();
        sb.append("🚄 【").append(fromCity).append("→").append(toCity).append("】去程车次推荐\n");
        sb.append(String.join("\n", departTrains)).append("\n\n");

        if (!returnDate.isBlank()) {
            List<String> returnTrains = generateTrains(toCity, fromCity, returnDate, preference);
            sb.append("🚄 【").append(toCity).append("→").append(fromCity).append("】返程车次推荐\n");
            sb.append(String.join("\n", returnTrains)).append("\n\n");
        }

        sb.append("💡 小贴士：建议提前 15 天购票，节假日票源紧张\n");
        sb.append("💰 票价仅供参考，实际以 12306 为准");

        return sb.toString();
    }

    private List<String> generateTrains(String from, String to, String date, String preference) {
        List<String> trains = new ArrayList<>();
        LocalDate departDate = LocalDate.parse(date);
        String dateStr = departDate.format(DATE_FMT);

        // 模拟计算里程时间
        int baseMinutes = calculateDuration(from, to);
        int basePrice = calculatePrice(from, to);

        String[][] templates = {
            {"G7001", "06:30", "特等座", "2"},
            {"G7005", "08:00", "二等座", "1"},
            {"D3001", "09:30", "二等座", "0.8"},
            {"G7015", "10:30", "一等座", "1.6"},
            {"G7023", "12:00", "二等座", "1"},
            {"G7031", "14:00", "商务座", "3"},
            {"D3015", "15:30", "二等座", "0.8"},
            {"G7045", "17:00", "一等座", "1.6"},
            {"G7055", "18:30", "二等座", "1"},
            {"G7065", "20:00", "二等座", "1"}
        };

        int count = 0;
        for (String[] t : templates) {
            if (count >= 5) break;
            int duration = baseMinutes + ThreadLocalRandom.current().nextInt(-10, 20);
            int price = (int) (basePrice * Double.parseDouble(t[3]));
            String departTime = t[1];
            int hour = Integer.parseInt(departTime.split(":")[0]);
            int min = Integer.parseInt(departTime.split(":")[1]) + duration % 60;
            int arrHour = hour + duration / 60 + min / 60;
            int arrMin = min % 60;
            String arriveTime = String.format("%02d:%02d", arrHour, arrMin);

            trains.add(String.format("%s %s %s-%s 用时%d分 %s ¥%d",
                dateStr, t[0], departTime, arriveTime, duration, t[2], price));
            count++;
        }

        return trains;
    }

    private int calculateDuration(String from, String to) {
        String key = from + "-" + to;
        return switch (key) {
            case "上海-无锡", "无锡-上海" -> 30;
            case "南京-无锡", "无锡-南京" -> 60;
            case "北京-无锡", "无锡-北京" -> 280;
            case "杭州-无锡", "无锡-杭州" -> 90;
            case "苏州-无锡", "无锡-苏州" -> 15;
            case "常州-无锡", "无锡-常州" -> 20;
            default -> 120;
        };
    }

    private int calculatePrice(String from, String to) {
        int duration = calculateDuration(from, to);
        return (int) (duration * 0.5 * 2); // 粗略估算
    }
}
