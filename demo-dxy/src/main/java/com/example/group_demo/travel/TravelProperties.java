package com.example.group_demo.travel;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "travel")
public class TravelProperties {

    private String pageDir = "data/trips";
    private String pageBaseUrl = "http://localhost:8080/api/trips";
    private boolean generateImage = true;
    private boolean generateVoice = true;

    public String getPageDir() {
        return pageDir;
    }

    public void setPageDir(String pageDir) {
        this.pageDir = pageDir;
    }

    public String getPageBaseUrl() {
        return pageBaseUrl;
    }

    public void setPageBaseUrl(String pageBaseUrl) {
        this.pageBaseUrl = pageBaseUrl;
    }

    public boolean isGenerateImage() {
        return generateImage;
    }

    public void setGenerateImage(boolean generateImage) {
        this.generateImage = generateImage;
    }

    public boolean isGenerateVoice() {
        return generateVoice;
    }

    public void setGenerateVoice(boolean generateVoice) {
        this.generateVoice = generateVoice;
    }
}
