package com.example.group_demo.meituan;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 美团开放平台接入配置。真实接口地址与签名规则请按申请到的文档填写。
 */
@ConfigurationProperties(prefix = "meituan")
public class MeituanProperties {

    private boolean enabled = true;
    private boolean mockEnabled = true;
    private String baseUrl = "https://openapi.meituan.com";
    private String appKey = "";
    private String secret = "";
    private String authToken = "";
    private String hotelEndpoint = "";
    private String foodEndpoint = "";
    private long cacheTtlSeconds = 3600;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    public void setMockEnabled(boolean mockEnabled) {
        this.mockEnabled = mockEnabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public String getAuthToken() {
        return authToken;
    }

    public void setAuthToken(String authToken) {
        this.authToken = authToken;
    }

    public String getHotelEndpoint() {
        return hotelEndpoint;
    }

    public void setHotelEndpoint(String hotelEndpoint) {
        this.hotelEndpoint = hotelEndpoint;
    }

    public String getFoodEndpoint() {
        return foodEndpoint;
    }

    public void setFoodEndpoint(String foodEndpoint) {
        this.foodEndpoint = foodEndpoint;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }
}
