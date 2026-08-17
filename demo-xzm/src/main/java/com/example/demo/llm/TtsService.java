package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 语音合成服务：CosyVoice，非流式调用，返回音频文件 URL 并下载为字节数据。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final DashScopeClient client;
    private final LlmProperties props;

    public TtsService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 将文本合成为语音，返回音频字节数据（格式由配置决定，默认 mp3）。
     */
    public byte[] synthesize(String text) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", props.getTts().getModel(),
                "input", Map.of(
                        "text", text,
                        "voice", props.getTts().getVoice()
                ),
                "parameters", Map.of(
                        "format", props.getTts().getFormat(),
                        "sample_rate", props.getTts().getSampleRate()
                )
        );

        JsonNode resp = client.postNative("/api/v1/services/audio/tts/SpeechSynthesizer", requestBody, false);
        String audioUrl = resp.path("output").path("audio").path("url").asText(null);
        if (audioUrl == null) {
            throw new RuntimeException("语音合成未返回音频URL: " + resp);
        }
        log.info("语音合成成功 url={}", audioUrl);
        return client.download(audioUrl);
    }
}
