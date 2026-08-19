package com.example.group_demo.tool;

import com.example.group_demo.news.NewsProperties;
import com.example.group_demo.news.NewsService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class HotNewsTool implements BotTool {

    private final NewsService newsService;
    private final NewsProperties properties;

    public HotNewsTool(NewsService newsService, NewsProperties properties) {
        this.newsService = newsService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "get_hot_news";
    }

    @Override
    public String description() {
        return "获取今日实时新闻热点摘要（每天 60 秒读懂世界），返回中文文本。"
            + "当用户询问今天有什么热点、今日新闻、热点资讯时调用。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "max_results", Map.of(
                    "type", "integer",
                    "description", "返回榜单条数，1-50，默认 10"
                )
            ),
            "required", List.of(),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        int maxItems = arguments.path("max_results").asInt(properties.getMaxItems());
        return newsService.getHotNews(maxItems);
    }
}
