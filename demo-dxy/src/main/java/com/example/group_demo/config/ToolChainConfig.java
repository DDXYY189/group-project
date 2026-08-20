package com.example.group_demo.config;

import com.example.group_demo.tool.chain.ToolChain;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

@Configuration
public class ToolChainConfig {

    @Bean
    public ToolChain weatherToTodoChain() {
        return new ToolChain(
            "weather_to_todo",
            "查询指定城市的天气，并把天气结果自动记入用户待办。当用户要求把天气情况记录到待办时调用。",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "location", Map.of("type", "string", "description", "城市名，例如：北京、上海、广州")
                ),
                "required", List.of("location"),
                "additionalProperties", false
            ),
            List.of(
                new ToolChain.Step("query_weather", "{\"location\": \"{{input.location}}\"}"),
                new ToolChain.Step("manage_todo", "{\"action\": \"add\", \"text\": \"{{prev.result}}\"}")
            )
        );
    }

    @Bean
    public ToolChain hotNewsToTodoChain() {
        return new ToolChain(
            "hot_news_to_todo",
            "获取今日热点，自动搜索第一条热点的详细信息，并把搜索摘要记入用户待办。当用户要求把热点或第一条新闻记入待办时调用。",
            Map.of(
                "type", "object",
                "properties", Map.of(),
                "required", List.of(),
                "additionalProperties", false
            ),
            List.of(
                new ToolChain.Step("get_hot_news", "{\"max_results\": 5}", "numbered_items"),
                new ToolChain.Step("web_search", "{\"query\": \"{{prev.first}}\", \"max_results\": 3}"),
                new ToolChain.Step("manage_todo", "{\"action\": \"add\", \"text\": \"{{prev.result}}\"}")
            )
        );
    }
}
