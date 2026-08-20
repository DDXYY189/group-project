package com.example.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "llm.dashscope")
public class LlmProperties {

    private String apiKey;
    private String baseUrl = "https://dashscope.aliyuncs.com";

    private Chat chat = new Chat();
    private Image image = new Image();
    private Tts tts = new Tts();
    private Asr asr = new Asr();
    private Vision vision = new Vision();
    private Weather weather = new Weather();
    private Intent intent = new Intent();

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

    public Chat getChat() {
        return chat;
    }

    public void setChat(Chat chat) {
        this.chat = chat;
    }

    public Image getImage() {
        return image;
    }

    public void setImage(Image image) {
        this.image = image;
    }

    public Tts getTts() {
        return tts;
    }

    public void setTts(Tts tts) {
        this.tts = tts;
    }

    public Asr getAsr() {
        return asr;
    }

    public void setAsr(Asr asr) {
        this.asr = asr;
    }

    public Vision getVision() {
        return vision;
    }

    public void setVision(Vision vision) {
        this.vision = vision;
    }

    public Weather getWeather() {
        return weather;
    }

    public void setWeather(Weather weather) {
        this.weather = weather;
    }

    public Intent getIntent() {
        return intent;
    }

    public void setIntent(Intent intent) {
        this.intent = intent;
    }

    public static class Chat {
        private String model = "qwen-plus";
        private String systemPrompt = "";
        private int maxHistory = 20;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }

        public int getMaxHistory() {
            return maxHistory;
        }

        public void setMaxHistory(int maxHistory) {
            this.maxHistory = maxHistory;
        }
    }

    public static class Image {
        private String model = "wanx-v1";
        private String size = "1024*1024";
        private int n = 1;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getSize() {
            return size;
        }

        public void setSize(String size) {
            this.size = size;
        }

        public int getN() {
            return n;
        }

        public void setN(int n) {
            this.n = n;
        }
    }

    public static class Tts {
        private String model = "cosyvoice-v3-flash";
        private String voice = "longhuhu_v3";
        // 非流式 HTTP API 默认返回 mp3@22050Hz，流式 WebSocket API 显式请求同样格式
        private String format = "mp3";
        private int sampleRate = 22050;

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getVoice() {
            return voice;
        }

        public void setVoice(String voice) {
            this.voice = voice;
        }

        public String getFormat() {
            return format;
        }

        public void setFormat(String format) {
            this.format = format;
        }

        public int getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
        }
    }

    public static class Asr {
        private String model = "qwen3-asr-flash";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Vision {
        // Qwen-VL-Plus：通义千问视觉理解模型，支持图片理解、OCR、图表解读
        private String model = "qwen-vl-plus";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }

    public static class Weather {
        private String apiKey;
        // 免费用户用 devapi，付费用户用 api
        private String host = "mu7jpk64xd.re.qweatherapi.com";
        // 免费用户 key 以 DEV 开头
        private int cacheMinutes = 30;

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getCacheMinutes() {
            return cacheMinutes;
        }

        public void setCacheMinutes(int cacheMinutes) {
            this.cacheMinutes = cacheMinutes;
        }
    }

    public static class Intent {
        private String model = "qwen-plus";

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }
    }
}
