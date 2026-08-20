package com.example.group_demo.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.group_demo.config.RestClientFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final int MAX_RESULTS = 50;

    private static final String SEARCH_PROMPT_TEMPLATE =
        "你是一位专业的联网搜索助手。请严格根据{search_result}中的搜索结果回答用户问题，遵循以下规则：\n"
        + "1. 只使用搜索结果中的信息，不要编造或使用模型自身知识。\n"
        + "2. 如果搜索结果不足以回答问题，请明确说明\"未找到相关信息\"。\n"
        + "3. 保留关键事实：时间、地点、人物、数字等具体细节。\n"
        + "4. 每条信息后标注来源链接（如有）。\n"
        + "5. 对于时效性问题，优先使用最新发布的信息。\n"
        + "6. 用简洁的中文回答，分点列出关键信息。";

    private final SearchProperties properties;
    private final RestClient restClient;

    public SearchService(SearchProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
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

        Map<String, Object> webSearchParams = new LinkedHashMap<>();
        webSearchParams.put("enable", true);
        webSearchParams.put("search_engine", properties.getSearchEngine());
        webSearchParams.put("search_result", true);
        webSearchParams.put("search_prompt", SEARCH_PROMPT_TEMPLATE);
        webSearchParams.put("count", String.valueOf(count));
        webSearchParams.put("search_recency_filter", properties.getRecencyFilter());
        webSearchParams.put("content_size", properties.getContentSize());

        Map<String, Object> searchTool = new LinkedHashMap<>();
        searchTool.put("type", "web_search");
        searchTool.put("web_search", webSearchParams);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("model", properties.getModel());
        requestBody.put("messages", List.of(
            Map.of("role", "user", "content", trimmed)
        ));
        requestBody.put("tools", List.of(searchTool));
        requestBody.put("temperature", 0.1);
        requestBody.put("top_p", 0.9);

        log.info("联网搜索 query=\"{}\" engine={} count={} model={}",
            trimmed, properties.getSearchEngine(), count, properties.getModel());

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
        log.info("联网搜索成功 query=\"{}\" reply_length={}", trimmed, reply.length());
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
