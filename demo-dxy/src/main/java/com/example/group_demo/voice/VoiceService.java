package com.example.group_demo.voice;

import com.example.group_demo.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
        Path ffmpeg = resolveToolPath(voiceProperties.getFfmpegPath());

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

    public byte[] synthesizeToWav(String text) throws IOException, InterruptedException {
        Path mp3 = Files.createTempFile("tts-", ".mp3");
        Path wav = Files.createTempFile("tts-", ".wav");
        try {
            Process tts = new ProcessBuilder(
                voiceProperties.getPythonPath(),
                "-m", "edge_tts",
                "--voice", voiceProperties.getTtsVoice(),
                "--text", text,
                "--write-media", mp3.toString()
            ).redirectErrorStream(true).start();
            String ttsLog = new String(tts.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (tts.waitFor() != 0) {
                throw new IOException("TTS 合成失败: " + ttsLog);
            }

            Path ffmpeg = resolveToolPath(voiceProperties.getFfmpegPath());
            Process convert = new ProcessBuilder(
                ffmpeg.toString(),
                "-y",
                "-i", mp3.toString(),
                "-ar", String.valueOf(voiceProperties.getSilkPcmRate()),
                "-ac", "1",
                wav.toString()
            ).redirectErrorStream(true).start();
            String convertLog = new String(convert.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (convert.waitFor() != 0) {
                throw new IOException("WAV 转换失败: " + convertLog);
            }
            byte[] wavBytes = Files.readAllBytes(wav);
            if (wavBytes.length == 0) {
                throw new IOException("TTS 输出为空");
            }
            return wavBytes;
        } finally {
            Files.deleteIfExists(mp3);
            Files.deleteIfExists(wav);
        }
    }

    public byte[] synthesizeToMp3(String text) throws IOException, InterruptedException {
        Path mp3 = Files.createTempFile("tts-", ".mp3");
        try {
            Process tts = new ProcessBuilder(
                voiceProperties.getPythonPath(),
                "-m", "edge_tts",
                "--voice", voiceProperties.getTtsVoice(),
                "--text", text,
                "--write-media", mp3.toString()
            ).redirectErrorStream(true).start();
            String ttsLog = new String(tts.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (tts.waitFor() != 0) {
                throw new IOException("TTS 合成失败: " + ttsLog);
            }
            byte[] mp3Bytes = Files.readAllBytes(mp3);
            if (mp3Bytes.length == 0) {
                throw new IOException("TTS 输出为空");
            }
            return mp3Bytes;
        } finally {
            Files.deleteIfExists(mp3);
        }
    }

    public byte[] toSilk(byte[] wavBytes) throws IOException, InterruptedException {
        Path wav = Files.createTempFile("silk-in-", ".wav");
        Path silk = Files.createTempFile("silk-out-", ".silk");
        try {
            Files.write(wav, wavBytes);
            String code = "import pilk; pilk.encode(r'" + wav + "', r'" + silk + "', pcm_rate="
                + voiceProperties.getSilkPcmRate() + ", tencent=False)";
            Process process = new ProcessBuilder(voiceProperties.getPythonPath(), "-c", code)
                .redirectErrorStream(true).start();
            String processLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("SILK 编码失败: " + processLog);
            }
            byte[] silkBytes = Files.readAllBytes(silk);
            if (silkBytes.length == 0) {
                throw new IOException("SILK 编码结果为空");
            }
            log.info("SILK 编码完成 size={} header={}", silkBytes.length, toHex(silkBytes));
            return silkBytes;
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(silk);
        }
    }

    public byte[] toAmrWb(byte[] wavBytes) throws IOException, InterruptedException {
        Path wav = Files.createTempFile("amr-in-", ".wav");
        Path amr = Files.createTempFile("amr-out-", ".amr");
        try {
            Files.write(wav, wavBytes);
            Process process = new ProcessBuilder(
                resolveToolPath(voiceProperties.getFfmpegPath()).toString(),
                "-y",
                "-i", wav.toString(),
                "-ar", String.valueOf(voiceProperties.getSendSampleRate()),
                "-ac", "1",
                "-c:a", "libvo_amrwbenc",
                amr.toString()
            ).redirectErrorStream(true).start();
            String processLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("AMR-WB 编码失败: " + processLog);
            }
            byte[] amrBytes = Files.readAllBytes(amr);
            if (amrBytes.length == 0) {
                throw new IOException("AMR-WB 编码结果为空");
            }
            log.info("AMR-WB 编码完成 size={} header={}", amrBytes.length, toHex(amrBytes));
            return amrBytes;
        } finally {
            Files.deleteIfExists(wav);
            Files.deleteIfExists(amr);
        }
    }

    public byte[] decodeSilkToWav(byte[] silkBytes) throws IOException, InterruptedException {
        Path silk = Files.createTempFile("silk-in-", ".silk");
        Path pcm = Files.createTempFile("silk-out-", ".pcm");
        try {
            Files.write(silk, silkBytes);
            String code = "import pilk; pilk.decode(r'" + silk + "', r'" + pcm + "', pcm_rate="
                + voiceProperties.getSilkPcmRate() + ")";
            Process process = new ProcessBuilder(voiceProperties.getPythonPath(), "-c", code)
                .redirectErrorStream(true).start();
            String processLog = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("SILK 解码失败: " + processLog);
            }
            byte[] pcmBytes = Files.readAllBytes(pcm);
            if (pcmBytes.length == 0) {
                throw new IOException("SILK 解码结果为空");
            }
            return pcmToWav(pcmBytes, voiceProperties.getSilkPcmRate());
        } finally {
            Files.deleteIfExists(silk);
            Files.deleteIfExists(pcm);
        }
    }

    private byte[] pcmToWav(byte[] pcm, int sampleRate) {
        int channels = 1;
        int bitsPerSample = 16;
        int dataSize = pcm.length;
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;

        ByteBuffer buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(36 + dataSize);
        buffer.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buffer.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(16);
        buffer.putShort((short) 1);
        buffer.putShort((short) channels);
        buffer.putInt(sampleRate);
        buffer.putInt(byteRate);
        buffer.putShort((short) blockAlign);
        buffer.putShort((short) bitsPerSample);
        buffer.put("data".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(dataSize);
        buffer.put(pcm);
        return buffer.array();
    }

    public int getSilkPcmRate() {
        return voiceProperties.getSilkPcmRate();
    }

    public int getSendEncodeType() {
        return voiceProperties.getSendEncodeType();
    }

    public int getSendSampleRate() {
        return voiceProperties.getSendSampleRate();
    }

    private Path resolveToolPath(String path) {
        Path p = Path.of(path);
        if (Files.exists(p)) {
            return p;
        }
        Path abs = p.toAbsolutePath();
        if (Files.exists(abs)) {
            return abs;
        }
        Path parent = Path.of("..", path).toAbsolutePath();
        if (Files.exists(parent)) {
            return parent;
        }
        throw new IllegalStateException("工具未找到: " + path + " (user.dir="
            + Path.of("").toAbsolutePath() + ")");
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(16, bytes.length); i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
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
