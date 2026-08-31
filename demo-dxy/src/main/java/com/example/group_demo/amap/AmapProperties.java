package com.example.group_demo.amap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 高德地图配置：
 * rest-key 用于 Web 服务 REST API（地理编码、路径规划、静态地图）；
 * js-key 用于网页端 JS API（可交互地图），需在高德控制台单独申请「Web端(JS API)」类型的 key，
 * 并在控制台为该 key 配置「域名白名单」限制前端盗用；security-js-code 为其安全密钥。
 */
@ConfigurationProperties(prefix = "amap")
public class AmapProperties {

    private boolean enabled = true;
    private String restKey = "";
    private String baseUrl = "https://restapi.amap.com";
    private String routeMode = "walking";
    private long cacheTtlSeconds = 3600;
    private String jsKey = "";
    private String securityJsCode = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRestKey() {
        return restKey;
    }

    public void setRestKey(String restKey) {
        this.restKey = restKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public String getJsKey() {
        return jsKey;
    }

    public void setJsKey(String jsKey) {
        this.jsKey = jsKey;
    }

    public String getSecurityJsCode() {
        return securityJsCode;
    }

    public void setSecurityJsCode(String securityJsCode) {
        this.securityJsCode = securityJsCode;
    }

    public boolean isJsEnabled() {
        return jsKey != null && !jsKey.isBlank();
    }
}
