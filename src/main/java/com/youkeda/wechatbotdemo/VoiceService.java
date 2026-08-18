package com.youkeda.wechatbotdemo;

import com.alibaba.dashscope.audio.tts.SpeechSynthesisAudioFormat;
import com.alibaba.dashscope.audio.tts.SpeechSynthesisParam;
import com.alibaba.dashscope.audio.tts.SpeechSynthesizer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 语音合成服务：
 * 1. 调用阿里云百炼 TTS（sambert-zhichu-v1）把文字合成为 16kHz WAV
 * 2. 提取 PCM 数据
 * 3. 调用 tools/silk_v3_encoder.exe 转码成微信语音消息所需的 SILK 格式
 */
public class VoiceService {

    /** TTS 采样率（Hz），与 silk 编码 -Fs_API 保持一致 */
    public static final int SAMPLE_RATE = 16000;

    private final String apiKey;
    private final SpeechSynthesizer synthesizer = new SpeechSynthesizer();
    private final String encoderPath;

    public VoiceService(String apiKey) {
        this(apiKey, defaultEncoderPath());
    }

    public VoiceService(String apiKey, String encoderPath) {
        this.apiKey = apiKey;
        this.encoderPath = encoderPath;
    }

    private static String defaultEncoderPath() {
        String userDir = System.getProperty("user.dir", ".");
        return userDir + File.separator + "tools" + File.separator + "silk_v3_encoder.exe";
    }

    /** 语音合成结果 */
    public static final class SilkVoiceResult {
        public final byte[] silkBytes;
        public final int playTimeMs;
        public final int sampleRate;

        SilkVoiceResult(byte[] silkBytes, int playTimeMs, int sampleRate) {
            this.silkBytes = silkBytes;
            this.playTimeMs = playTimeMs;
            this.sampleRate = sampleRate;
        }
    }

    /**
     * 把一段文字合成为微信语音消息（SILK 格式）。
     *
     * @param text 要朗读的文字
     * @return SILK 音频字节、时长（毫秒）、采样率
     */
    public SilkVoiceResult synthesizeToSilk(String text) throws Exception {
        byte[] wavBytes = synthesizeWav(text);
        byte[] pcmBytes = extractPcmFromWav(wavBytes);

        int playTimeMs = (int) ((long) pcmBytes.length * 1000 / (SAMPLE_RATE * 2));
        byte[] silkBytes = pcmToSilk(pcmBytes);

        System.out.println("语音合成完成，文本长度=" + text.length()
                + "，时长=" + playTimeMs + "ms，silk 大小=" + silkBytes.length + " 字节");
        return new SilkVoiceResult(silkBytes, playTimeMs, SAMPLE_RATE);
    }

    /** 第 1 步：TTS 合成 WAV */
    private byte[] synthesizeWav(String text) throws Exception {
        SpeechSynthesisParam param = SpeechSynthesisParam.builder()
                .model("sambert-zhichu-v1")
                .text(text)
                .format(SpeechSynthesisAudioFormat.WAV)
                .sampleRate(SAMPLE_RATE)
                .apiKey(apiKey)
                .build();

        ByteBuffer audioBuffer = synthesizer.call(param);
        byte[] wavBytes = new byte[audioBuffer.remaining()];
        audioBuffer.get(wavBytes);
        if (wavBytes.length == 0) {
            throw new RuntimeException("TTS 返回空音频");
        }
        System.out.println("TTS 合成完成，WAV 大小=" + wavBytes.length + " 字节");
        return wavBytes;
    }

    /** 第 2 步：从 WAV 中提取 PCM 裸数据（跳过 RIFF/fmt/data 等头） */
    private byte[] extractPcmFromWav(byte[] wav) {
        for (int i = 12; i + 8 <= wav.length; i++) {
            if (wav[i] == 'd' && wav[i + 1] == 'a' && wav[i + 2] == 't' && wav[i + 3] == 'a') {
                int dataOffset = i + 8;
                byte[] pcm = new byte[wav.length - dataOffset];
                System.arraycopy(wav, dataOffset, pcm, 0, pcm.length);
                return pcm;
            }
        }
        throw new RuntimeException("WAV 中找不到 data 数据块，无法提取 PCM");
    }

    /** 第 3 步：PCM → SILK 转码（调用 silk_v3_encoder.exe） */
    private byte[] pcmToSilk(byte[] pcmBytes) throws Exception {
        File encoder = new File(encoderPath);
        if (!encoder.isFile()) {
            throw new IOException("找不到 silk 编码器: " + encoderPath
                    + "，请确认 tools/silk_v3_encoder.exe 存在");
        }

        Path tempDir = Files.createTempDirectory("silk-tts");
        try {
            Path pcmPath = tempDir.resolve("input.pcm");
            Path silkPath = tempDir.resolve("output.silk");
            Files.write(pcmPath, pcmBytes);

            ProcessBuilder pb = new ProcessBuilder(
                    encoder.getAbsolutePath(),
                    pcmPath.toString(),
                    silkPath.toString(),
                    "-Fs_API", String.valueOf(SAMPLE_RATE),
                    "-tencent",
                    "-quiet");
            pb.redirectErrorStream(true);
            Process process = pb.start();

            String output = readAll(process.getInputStream());
            int exitCode = process.waitFor();

            if (exitCode != 0 || !Files.exists(silkPath) || Files.size(silkPath) == 0) {
                throw new RuntimeException("SILK 转码失败，退出码=" + exitCode + "，输出: " + output);
            }
            return Files.readAllBytes(silkPath);
        } finally {
            deleteRecursively(tempDir.toFile());
        }
    }

    private static String readAll(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int length;
        while ((length = inputStream.read(chunk)) != -1) {
            buffer.write(chunk, 0, length);
        }
        return buffer.toString("UTF-8");
    }

    private static void deleteRecursively(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
