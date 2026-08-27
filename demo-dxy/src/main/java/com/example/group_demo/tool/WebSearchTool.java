package com.example.group_demo.tool;

import com.example.group_demo.search.SearchProperties;
import com.example.group_demo.search.SearchService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class WebSearchTool implements BotTool {

    private final SearchService searchService;
    private final SearchProperties properties;

    public WebSearchTool(SearchService searchService, SearchProperties properties) {
        this.searchService = searchService;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "web_search";
    }

    @Override
    public String description() {
        return "联网搜索互联网并返回搜索到的信息摘要。"
            + "当用户需要查询实时信息、最新新闻、人物事件或任何需要联网获取的内容时调用。"
            + "query 必须使用用户的原始问题，不要改写，不要添加预测、推测等词。";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "query", Map.of(
                    "type", "string",
                    "description", "搜索关键词或完整问题"
                ),
                "max_results", Map.of(
                    "type", "integer",
                    "description", "返回结果数量，1-10，默认 8"
                )
            ),
            "required", List.of("query"),
            "additionalProperties", false
        );
    }

    @Override
    public String execute(String userId, JsonNode arguments) {
        String query = arguments.path("query").asText("").trim();
        int maxResults = arguments.path("max_results").asInt(properties.getMaxResults());
        return searchService.search(query, maxResults);
    }

    @Override
    public boolean relayToUser() {
        return true;
    }
}
