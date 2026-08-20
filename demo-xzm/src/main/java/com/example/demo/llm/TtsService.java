package com.example.demo.llm;

import com.example.demo.config.LlmProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 语音合成服务。
 *
 * 支持三种模式：
 * 1. synthesize()：非流式 HTTP 调用（回退用）。
 * 2. synthesizeStream()：WebSocket 流式合成，文本一次性发送，音频按句子流式返回。
 * 3. startStream()：创建流式会话，支持增量送入文本（LLM 边生成边送入 TTS），
 *    实现"边生成音频边播放"，总延迟最低。
 */
@Service
public class TtsService {

    private static final Logger log = LoggerFactory.getLogger(TtsService.class);

    private final DashScopeClient client;
    private final LlmProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient wsHttpClient;

    public TtsService(DashScopeClient client, LlmProperties props) {
        this.client = client;
        this.props = props;
        this.wsHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 非流式语音合成结果，包含音频数据和实际格式（从 API 返回的 URL 中检测）。
     */
    public record TtsResult(byte[] audio, String format) {
    }

    /**
     * 非流式语音合成（回退用）。
     * HTTP API 不带 format 参数时默认返回 wav 格式，此方法从返回 URL 中检测实际格式。
     */
    public TtsResult synthesize(String text) throws Exception {
        Map<String, Object> requestBody = Map.of(
                "model", props.getTts().getModel(),
                "input", Map.of(
                        "text", text,
                        "voice", props.getTts().getVoice()
                )
        );
        JsonNode resp = client.postNative("/api/v1/services/audio/tts/SpeechSynthesizer", requestBody, false);
        String audioUrl = resp.path("output").path("audio").path("url").asText(null);
        if (audioUrl == null) {
            throw new RuntimeException("语音合成未返回音频URL: " + resp);
        }
        String format = detectFormatFromUrl(audioUrl);
        log.info("语音合成成功 url={} format={}", audioUrl, format);
        return new TtsResult(client.download(audioUrl), format);
    }

    /**
     * 从音频 URL 中检测格式（如 .wav → wav, .mp3 → mp3）。
     */
    private String detectFormatFromUrl(String url) {
        String lower = url.toLowerCase();
        if (lower.contains(".wav")) return "wav";
        if (lower.contains(".mp3")) return "mp3";
        if (lower.contains(".pcm")) return "pcm";
        if (lower.contains(".opus")) return "opus";
        return props.getTts().getFormat();
    }

    /**
     * 流式语音合成：通过 WebSocket 连接 CosyVoice，按句子实时生成音频。
     * 文本一次性发送，但音频按句子流式返回，比同步接口更快。
     */
    public byte[] synthesizeStream(String text) throws Exception {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文本不能为空");
        }
        StreamingTtsSession session = startStream();
        session.sendText(text);
        return session.finish();
    }

    /**
     * 创建流式 TTS 会话：建立 WebSocket 连接并发送 run-task。
     * 调用方可多次 sendText() 增量送入文本（如按句子），最后 finish() 获取完整音频。
     * TTS 在 LLM 仍在生成时就开始合成，实现"边生成音频边播放"。
     */
    public StreamingTtsSession startStream() throws Exception {
        String taskId = UUID.randomUUID().toString().replace("-", "");
        String wsUrl = props.getBaseUrl().replace("https://", "wss://") + "/api-ws/v1/inference";
        StreamingTtsSession session = new StreamingTtsSession(taskId, objectMapper, props);

        wsHttpClient.newWebSocketBuilder()
                .header("Authorization", "Bearer " + props.getApiKey())
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {

                    private final StringBuilder textBuffer = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        log.debug("TTS WebSocket 已连接，发送 run-task");
                        try {
                            session.sendRunTask(webSocket);
                        } catch (Exception e) {
                            session.onError("发送 run-task 失败: " + e.getMessage());
                            return;
                        }
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        textBuffer.append(data);
                        if (!last) {
                            webSocket.request(1);
                            return null;
                        }
                        String message = textBuffer.toString();
                        textBuffer.setLength(0);
                        try {
                            JsonNode event = objectMapper.readTree(message);
                            String eventType = event.path("header").path("event").asText("");
                            log.debug("TTS 服务端事件: {}", eventType);
                            switch (eventType) {
                                case "task-started" -> session.onTaskStarted();
                                case "task-finished" -> session.onTaskFinished();
                                case "task-failed" -> session.onError(
                                        event.path("header").path("error_message").asText("unknown"));
                                default -> { }
                            }
                        } catch (Exception e) {
                            session.onError("解析 TTS 事件失败: " + e.getMessage());
                        }
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        byte[] bytes = new byte[data.remaining()];
                        data.get(bytes);
                        session.onAudio(bytes);
                        log.debug("收到音频块 {} bytes", bytes.length);
                        webSocket.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        log.debug("TTS WebSocket 已关闭 statusCode={}", statusCode);
                        session.onTaskFinished();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        log.error("TTS WebSocket 错误", error);
                        session.onError(error.getMessage());
                    }
                })
                .join();

        return session;
    }

    /**
     * 流式 TTS 会话：封装 WebSocket 生命周期，支持增量送入文本。
     * 用法：startStream() → sendText("句子1") → sendText("句子2") → finish()
     */
    public static class StreamingTtsSession {

        private final String taskId;
        private final ObjectMapper mapper;
        private final LlmProperties props;
        private final List<byte[]> audioChunks = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch startedLatch = new CountDownLatch(1);
        private final CountDownLatch finishedLatch = new CountDownLatch(1);
        private volatile WebSocket webSocket;
        private volatile String error = null;

        StreamingTtsSession(String taskId, ObjectMapper mapper, LlmProperties props) {
            this.taskId = taskId;
            this.mapper = mapper;
            this.props = props;
        }

        void setWebSocket(WebSocket ws) {
            this.webSocket = ws;
        }

        void onTaskStarted() {
            startedLatch.countDown();
        }

        void onAudio(byte[] audio) {
            audioChunks.add(audio);
        }

        void onTaskFinished() {
            finishedLatch.countDown();
        }

        void onError(String message) {
            error = message;
            startedLatch.countDown();
            finishedLatch.countDown();
        }

        /**
         * 发送 run-task（由 WebSocket onOpen 回调调用）。
         */
        void sendRunTask(WebSocket ws) throws Exception {
            this.webSocket = ws;

            ObjectNode event = mapper.createObjectNode();
            ObjectNode header = event.putObject("header");
            header.put("action", "run-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");

            ObjectNode payload = event.putObject("payload");
            payload.put("task_group", "audio");
            payload.put("task", "tts");
            payload.put("function", "SpeechSynthesizer");
            payload.put("model", props.getTts().getModel());
            payload.putObject("input");

            ObjectNode parameters = payload.putObject("parameters");
            parameters.put("text_type", "PlainText");
            parameters.put("voice", props.getTts().getVoice());
            parameters.put("format", props.getTts().getFormat());
            parameters.put("sample_rate", props.getTts().getSampleRate());

            ws.sendText(mapper.writeValueAsString(event), true).join();
            log.debug("发送 run-task taskId={}", taskId);
        }

        /**
         * 增量送入文本：等待 task-started 后发送 continue-task。
         * 可多次调用，每次送入一段文本（如一个句子）。
         * TTS 会在收到完整句子后立即合成并返回音频。
         */
        public void sendText(String text) throws Exception {
            if (!startedLatch.await(30, TimeUnit.SECONDS)) {
                throw new RuntimeException("TTS 任务启动超时" + (error != null ? ": " + error : ""));
            }
            if (error != null) {
                throw new RuntimeException("TTS 错误: " + error);
            }
            if (webSocket == null) {
                throw new RuntimeException("TTS WebSocket 未初始化");
            }

            ObjectNode event = mapper.createObjectNode();
            ObjectNode header = event.putObject("header");
            header.put("action", "continue-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            ObjectNode input = event.putObject("payload").putObject("input");
            input.put("text", text);

            webSocket.sendText(mapper.writeValueAsString(event), true).join();
            log.debug("发送 continue-task text length={}", text.length());
        }

        /**
         * 完成合成：发送 finish-task，等待 task-finished，返回拼接后的完整音频。
         */
        public byte[] finish() throws Exception {
            if (webSocket == null) {
                throw new RuntimeException("TTS WebSocket 未初始化");
            }

            ObjectNode event = mapper.createObjectNode();
            ObjectNode header = event.putObject("header");
            header.put("action", "finish-task");
            header.put("task_id", taskId);
            header.put("streaming", "duplex");
            event.putObject("payload").putObject("input");

            webSocket.sendText(mapper.writeValueAsString(event), true).join();
            log.debug("发送 finish-task taskId={}", taskId);

            if (!finishedLatch.await(60, TimeUnit.SECONDS)) {
                webSocket.abort();
                throw new RuntimeException("TTS 合成超时（60秒）");
            }

            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }

            if (error != null) {
                throw new RuntimeException("TTS 失败: " + error);
            }
            if (audioChunks.isEmpty()) {
                throw new RuntimeException("TTS 未返回音频数据");
            }

            int totalSize = 0;
            for (byte[] chunk : audioChunks) {
                totalSize += chunk.length;
            }
            byte[] audio = new byte[totalSize];
            int offset = 0;
            for (byte[] chunk : audioChunks) {
                System.arraycopy(chunk, 0, audio, offset, chunk.length);
                offset += chunk.length;
            }

            log.info("流式语音合成完成: {} bytes, {} chunks", audio.length, audioChunks.size());
            return audio;
        }
    }
}
