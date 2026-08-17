package com.example.group_demo.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    private String ffmpegPath = "tools/ffmpeg/ffmpeg.exe";
    private String asrUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private String asrModel = "qwen-omni-turbo";

    public String getFfmpegPath() {
        return ffmpegPath;
    }

    public void setFfmpegPath(String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    public String getAsrUrl() {
        return asrUrl;
    }

    public void setAsrUrl(String asrUrl) {
        this.asrUrl = asrUrl;
    }

    public String getAsrModel() {
        return asrModel;
    }

    public void setAsrModel(String asrModel) {
        this.asrModel = asrModel;
    }
}
