package com.example.group_demo.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_RESULTS = 10;
    private static final String SEARCH_PROMPT =
        "你是一个联网搜索助手。请根据搜索结果用简洁的中文回答用户问题，尽量保留关键事实；如果结果中有来源链接，请一并列出。";

    private final SearchProperties properties;
    private final RestClient restClient;

    public SearchService(SearchProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    public String search(String query, int maxResults) {
        String trimmed = query == null ? "" : query.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("搜索关键词不能为空");
        }
        String apiKey = properties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("搜索 API key 未配置");
        }

        int count = Math.max(1, Math.min(MAX_RESULTS, maxResults));
        Map<String, Object> searchTool = Map.of(
            "type", "web_search",
            "web_search", Map.of(
                "search_query", trimmed,
                "search_top_k", count
            )
        );
        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("messages", List.of(
            Map.of("role", "system", "content", SEARCH_PROMPT),
            Map.of("role", "user", "content", trimmed)
        ));
        requestBody.put("tools", List.of(searchTool));

        ChatResponse response = restClient.post()
            .uri(properties.getBaseUrl())
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(ChatResponse.class);

        String content = firstContent(response);
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("搜索接口返回为空");
        }
        String reply = content.trim();
        log.info("联网搜索成功 query={} count={}", trimmed, count);
        return reply;
    }

    private String firstContent(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return null;
        }
        ChatResponse.Choice choice = response.choices().get(0);
        return choice == null || choice.message() == null ? null : choice.message().content();
    }

    public record ChatResponse(List<Choice> choices) {
        public record Choice(Message message) {
        }

        public record Message(String role, String content) {
        }
    }
}
