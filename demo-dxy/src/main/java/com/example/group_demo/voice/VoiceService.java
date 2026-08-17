package com.example.group_demo.voice;

import com.example.group_demo.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final LlmProperties llmProperties;
    private final VoiceProperties voiceProperties;
    private final RestClient restClient;

    public VoiceService(LlmProperties llmProperties, VoiceProperties voiceProperties) {
        this.llmProperties = llmProperties;
        this.voiceProperties = voiceProperties;
        this.restClient = RestClient.builder().build();
    }

    public byte[] toWav(byte[] audioBytes, String sourceSuffix) throws IOException, InterruptedException {
        Path ffmpeg = Path.of(voiceProperties.getFfmpegPath());
        if (!Files.exists(ffmpeg)) {
            throw new IllegalStateException("FFmpeg 未找到: " + ffmpeg.toAbsolutePath());
        }

        String suffix = (sourceSuffix == null || sourceSuffix.isBlank()) ? ".silk" : sourceSuffix;
        Path input = Files.createTempFile("voice-in-", suffix);
        Path output = Files.createTempFile("voice-out-", ".wav");
        try {
            Files.write(input, audioBytes);
            Process process = new ProcessBuilder(
                ffmpeg.toString(),
                "-y",
                "-i", input.toString(),
                "-ar", "16000",
                "-ac", "1",
                output.toString()
            ).redirectErrorStream(true).start();
            String processLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("FFmpeg 转换失败: " + processLog);
            }
            byte[] wav = Files.readAllBytes(output);
            if (wav.length == 0) {
                throw new IOException("FFmpeg 转换结果为空");
            }
            return wav;
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
        }
    }

    public String transcribe(byte[] wavBytes) {
        String apiKey = llmProperties.getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("LLM API key 未配置");
        }

        String dataUri = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wavBytes);
        Map<String, Object> requestBody = Map.of(
            "model", voiceProperties.getAsrModel(),
            "input", Map.of("messages", List.of(
                Map.of("role", "user", "content", List.of(
                    Map.of("type", "audio", "audio", dataUri),
                    Map.of("type", "text", "text", "请转写这段语音，只输出文字内容")
                ))
            )),
            "parameters", Map.of("result_format", "message")
        );

        AsrResponse response = restClient.post()
            .uri(voiceProperties.getAsrUrl())
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(AsrResponse.class);

        if (response == null || response.output() == null
            || response.output().choices() == null || response.output().choices().isEmpty()
            || response.output().choices().get(0).message() == null
            || response.output().choices().get(0).message().content() == null
            || response.output().choices().get(0).message().content().isEmpty()) {
            throw new IllegalStateException("语音识别返回结果为空");
        }

        String text = response.output().choices().get(0).message().content().get(0).text();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("语音识别结果为空");
        }
        log.info("语音转写成功 text={}", text.trim());
        return text.trim();
    }

    public record AsrResponse(Output output) {
        public record Output(List<Choice> choices) {
        }

        public record Choice(Message message) {
        }

        public record Message(List<ContentPart> content) {
        }

        public record ContentPart(String text) {
        }
    }
}
