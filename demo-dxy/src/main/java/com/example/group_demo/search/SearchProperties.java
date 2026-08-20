package com.example.group_demo.search;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "search")
public class SearchProperties {

    private String apiKey = "";
    private String baseUrl = "https://open.bigmodel.cn/api/coding/paas/v4/chat/completions";
    private String model = "glm-5.3";
    private int maxResults = 10;
    private String searchEngine = "search_pro";
    private String recencyFilter = "noLimit";
    private String contentSize = "high";

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public String getSearchEngine() {
        return searchEngine;
    }

    public void setSearchEngine(String searchEngine) {
        this.searchEngine = searchEngine;
    }

    public String getRecencyFilter() {
        return recencyFilter;
    }

    public void setRecencyFilter(String recencyFilter) {
        this.recencyFilter = recencyFilter;
    }

    public String getContentSize() {
        return contentSize;
    }

    public void setContentSize(String contentSize) {
        this.contentSize = contentSize;
    }
}
