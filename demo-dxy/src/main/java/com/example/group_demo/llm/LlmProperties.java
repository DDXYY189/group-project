package com.example.group_demo.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm")
public class LlmProperties {

    private String apiKey = "";
    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";
    private String model = "qwen-plus";
    private String visionModel = "qwen-vl-plus";
    private int toolMaxRounds = 5;
    private Memory memory = new Memory();

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

    public String getVisionModel() {
        return visionModel;
    }

    public void setVisionModel(String visionModel) {
        this.visionModel = visionModel;
    }

    public int getToolMaxRounds() {
        return toolMaxRounds;
    }

    public void setToolMaxRounds(int toolMaxRounds) {
        this.toolMaxRounds = toolMaxRounds;
    }

    public Memory getMemory() {
        return memory;
    }

    public void setMemory(Memory memory) {
        this.memory = memory;
    }

    public static class Memory {
        private int maxTurns = 20;
        private long ttlMinutes = 60;
        private boolean summaryEnabled = true;
        private int summaryThresholdTurns = 30;
        private int recentTurns = 10;

        public int getMaxTurns() {
            return maxTurns;
        }

        public void setMaxTurns(int maxTurns) {
            this.maxTurns = maxTurns;
        }

        public long getTtlMinutes() {
            return ttlMinutes;
        }

        public void setTtlMinutes(long ttlMinutes) {
            this.ttlMinutes = ttlMinutes;
        }

        public boolean isSummaryEnabled() {
            return summaryEnabled;
        }

        public void setSummaryEnabled(boolean summaryEnabled) {
            this.summaryEnabled = summaryEnabled;
        }

        public int getSummaryThresholdTurns() {
            return summaryThresholdTurns;
        }

        public void setSummaryThresholdTurns(int summaryThresholdTurns) {
            this.summaryThresholdTurns = summaryThresholdTurns;
        }

        public int getRecentTurns() {
            return recentTurns;
        }

        public void setRecentTurns(int recentTurns) {
            this.recentTurns = recentTurns;
        }
    }
}
