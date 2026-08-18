package com.example.group_demo.voice;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice")
public class VoiceProperties {

    private String ffmpegPath = "tools/ffmpeg/ffmpeg.exe";
    private String asrUrl = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation";
    private String asrModel = "qwen-omni-turbo";
    private String pythonPath = "python";
    private String ttsVoice = "zh-CN-XiaoxiaoNeural";
    private int silkPcmRate = 16000;
    private int sendEncodeType = 4;
    private int sendSampleRate = 16000;

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

    public String getPythonPath() {
        return pythonPath;
    }

    public void setPythonPath(String pythonPath) {
        this.pythonPath = pythonPath;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public int getSilkPcmRate() {
        return silkPcmRate;
    }

    public void setSilkPcmRate(int silkPcmRate) {
        this.silkPcmRate = silkPcmRate;
    }

    public int getSendEncodeType() {
        return sendEncodeType;
    }

    public void setSendEncodeType(int sendEncodeType) {
        this.sendEncodeType = sendEncodeType;
    }

    public int getSendSampleRate() {
        return sendSampleRate;
    }

    public void setSendSampleRate(int sendSampleRate) {
        this.sendSampleRate = sendSampleRate;
    }
}
