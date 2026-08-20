package com.example.group_demo.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.example.group_demo.config.RestClientFactory;
import java.util.List;

@Service
public class NewsService {

    private static final Logger log = LoggerFactory.getLogger(NewsService.class);
    private static final int MAX_ITEMS = 50;

    private final NewsProperties properties;
    private final RestClient restClient;

    public NewsService(NewsProperties properties) {
        this.properties = properties;
        this.restClient = RestClientFactory.builder().build();
    }

    public String getHotNews(int maxItems) {
        NewsResponse response = restClient.get()
            .uri(properties.getBaseUrl())
            .retrieve()
            .body(NewsResponse.class);

        Integer code = response == null ? null : response.code();
        if (code == null || code != 200 || response.data() == null
            || response.data().news() == null || response.data().news().isEmpty()) {
            throw new IllegalStateException("热点接口返回为空");
        }

        int limit = Math.max(1, Math.min(MAX_ITEMS, maxItems));
        List<String> news = response.data().news().stream().limit(limit).toList();
        String date = textOr(response.data().date(), "今日");
        StringBuilder builder = new StringBuilder("每日热点（" + date + "）Top " + news.size() + "：\n");
        for (int i = 0; i < news.size(); i++) {
            builder.append(i + 1).append(". ").append(news.get(i).trim()).append("\n");
        }
        String reply = builder.toString().trim();
        log.info("热点查询成功 date={} count={}", date, news.size());
        return reply;
    }

    private String textOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback.trim();
        }
        return value.trim();
    }

    public record NewsResponse(Integer code, String message, NewsData data) {
    }

    public record NewsData(String date, List<String> news) {
    }
}
