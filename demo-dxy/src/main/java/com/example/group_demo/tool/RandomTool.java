package com.example.group_demo.tool;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class RandomTool implements BotTool {

    @Override
    public String name() {
        return "generate_random";
    }

    @Override
    public String description() {
        return "生成指定范围内的随机整数，当用户要求抽签、随机数、掷骰子时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "min", Map.of(
                                "type", "integer",
                                "description", "最小值，默认为 1"
                        ),
                        "max", Map.of(
                                "type", "integer",
                                "description", "最大值，默认为 100"
                        )
                ),
                "required", List.of("max"),
                "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        int min = arguments.path("min").asInt(1);
        int max = arguments.path("max").asInt(100);
        if (min >= max) {
            throw new IllegalArgumentException("最小值必须小于最大值");
        }
        int result = ThreadLocalRandom.current().nextInt(min, max + 1);
        return "随机结果：" + min + " 到 " + max + " 之间，抽到了 " + result;
    }
}
