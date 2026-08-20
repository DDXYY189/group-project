package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 语音识别服务：Qwen3-ASR-Flash，通过 OpenAI 兼容接口提交 Base64 音频进行识别。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final DashScopeClient client;
    private final LlmProperties props;

    public AsrService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
    }

    /**
     * 识别音频字节数据，返回文本。
     *
     * @throws UnsupportedAudioFormatException 当音频格式无法识别（如微信 silk 编码）时抛出
     */
    public String recognize(byte[] audio) throws Exception {
        String mediaType = detectMediaType(audio);
        if (mediaType == null) {
            throw new UnsupportedAudioFormatException(
                    "微信语音为 silk 编码，当前 ASR 无法直接识别。建议发送文字消息，或让微信端确保语音以 amr/mp3 编码下发。");
        }

        log.info("语音识别开始 格式={} 字节数={}", mediaType, audio.length);
        String dataUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(audio);
        Map<String, Object> request = Map.of(
                "model", props.getAsr().getModel(),
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", List.of(Map.of(
                                "type", "input_audio",
                                "input_audio", Map.of("data", dataUrl)
                        ))
                ))
        );

        JsonNode resp = client.chatCompletions(request);
        String text = resp.path("choices").path(0).path("message").path("content").asText("").trim();
        log.info("语音识别完成: {}", text);
        return text;
    }

    /**
     * 通过魔术字节判断音频 MIME 类型。
     * 支持 wav/mp3/amr/ogg；silk 格式（微信常见）返回 null，需额外转码。
     */
    private String detectMediaType(byte[] audio) {
        if (audio == null || audio.length < 4) {
            return null;
        }
        int len = Math.min(audio.length, 12);
        String head = new String(audio, 0, len);
        // RIFF .... WAVE -> WAV
        if (audio.length >= 4 && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F') {
            return "audio/wav";
        }
        // ID3 tag 或 0xFF 0xFB 开头 -> MP3
        if ((audio[0] == 0x49 && audio[1] == 0x44 && audio[2] == 0x33) || (audio[0] == (byte) 0xFF)) {
            return "audio/mpeg";
        }
        if (head.startsWith("#!AMR")) {
            return "audio/amr";
        }
        if (head.startsWith("OggS")) {
            return "audio/ogg";
        }
        // silk 编码：微信语音常见，ASR 不支持
        if (head.startsWith("#!SILK") || head.startsWith(" SILK") || head.startsWith("SILK")) {
            return null;
        }
        // 兜底：尝试按 amr 处理（微信较新版本默认 amr）
        return "audio/amr";
    }

    public static class UnsupportedAudioFormatException extends Exception {
        public UnsupportedAudioFormatException(String message) {
            super(message);
        }
    }
}
