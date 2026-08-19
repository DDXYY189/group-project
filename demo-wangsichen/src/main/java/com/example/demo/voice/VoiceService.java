package com.example.demo.voice;

import com.example.demo.config.VoiceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

@Service
public class VoiceService {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    private final VoiceProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VoiceService(VoiceProperties properties) {
        this.properties = properties;
    }

    public VoiceResult textToSilk(String text) {
        Path silkFile = null;
        Path mp3File = null;
        Path errFile = null;
        try {
            silkFile = Files.createTempFile("wechat-reply-", ".silk");
            mp3File = Files.createTempFile("wechat-reply-", ".mp3");
            errFile = Files.createTempFile("wechat-reply-err-", ".log");

            String scriptPath = resolveScriptPath().toString();

            ProcessBuilder builder = new ProcessBuilder(
                    properties.getNodeExecutable(),
                    scriptPath,
                    text,
                    silkFile.toString(),
                    mp3File.toString());
            builder.redirectError(errFile.toFile());

            Process process = builder.start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(90, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                throw new IllegalStateException("语音合成超时");
            }
            if (process.exitValue() != 0) {
                String stderr = Files.readString(errFile, StandardCharsets.UTF_8);
                throw new IllegalStateException("语音合成失败：" + stderr.trim());
            }

            JsonNode meta = objectMapper.readTree(stdout.trim());
            int durationMs = meta.path("durationMs").asInt();
            int sampleRate = meta.path("sampleRate").asInt();
            byte[] silkBytes = Files.readAllBytes(silkFile);
            byte[] mp3Bytes =
                    Files.exists(mp3File) && Files.size(mp3File) > 0
                            ? Files.readAllBytes(mp3File)
                            : null;

            return new VoiceResult(silkBytes, mp3Bytes, durationMs, sampleRate);
        } catch (Exception e) {
            log.error("语音合成失败", e);
            return null;
        } finally {
            deleteQuietly(silkFile);
            deleteQuietly(mp3File);
            deleteQuietly(errFile);
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }

    private Path resolveScriptPath() {
        Path configured = Paths.get(properties.getScriptPath());
        Path fromCwd = configured.toAbsolutePath();
        if (Files.exists(fromCwd)) {
            return fromCwd;
        }

        try {
            URL location = getClass().getProtectionDomain().getCodeSource().getLocation();
            if (location != null) {
                Path dir = Paths.get(location.toURI());
                if (!Files.isDirectory(dir)) {
                    dir = dir.getParent();
                }
                while (dir != null) {
                    Path candidate = dir.resolve(properties.getScriptPath());
                    if (Files.exists(candidate)) {
                        return candidate.toAbsolutePath();
                    }
                    dir = dir.getParent();
                }
            }
        } catch (Exception ignored) {
        }

        return fromCwd;
    }
}
