package com.example.demo.wechat;

import com.example.demo.config.LlmProperties;
import com.example.demo.llm.AsrService;
import com.example.demo.llm.ChatService;
import com.example.demo.llm.ImageGenerationService;
import com.example.demo.llm.TtsService;
import com.example.demo.llm.VisionService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * 消息路由：Function Calling 模式，LLM 自主决定调用工具或直接回复。
 *
 * 双通道并行：LLM 生成完整文本后，文字和语音同时输出。
 *   1. 文字通道：立即发送文本回复
 *   2. 语音通道：TTS 合成 MP3 音频后发送语音消息（后台异步）
 */
@Service
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    private final ILinkClient client;
    private final ChatService chatService;
    private final ImageGenerationService imageService;
    private final TtsService ttsService;
    private final AsrService asrService;
    private final VisionService visionService;
    private final LlmProperties llmProps;

    public MessageHandler(ILinkClient client, ChatService chatService,
                          ImageGenerationService imageService, TtsService ttsService,
                          AsrService asrService, VisionService visionService,
                          LlmProperties llmProps) {
        this.client = client;
        this.chatService = chatService;
        this.imageService = imageService;
        this.ttsService = ttsService;
        this.asrService = asrService;
        this.visionService = visionService;
        this.llmProps = llmProps;
    }

    public void handle(WeixinMessage msg) {
        if (msg == null || msg.getFrom_user_id() == null || msg.getItem_list() == null) {
            return;
        }
        String userId = msg.getFrom_user_id();
        for (MessageItem item : msg.getItem_list()) {
            try {
                if (item.getText_item() != null) {
                    handleText(userId, item.getText_item().getText());
                } else if (item.getVoice_item() != null) {
                    handleVoice(userId, msg, item);
                } else if (item.getImage_item() != null) {
                    handleImage(userId, item);
                }
            } catch (Exception e) {
                log.error("处理消息失败 userId={}: {}", userId, e.getMessage(), e);
                sendText(userId, "处理消息时出错: " + e.getMessage());
            }
        }
    }

    /**
     * 文本消息处理：Function Calling 模式。
     * 关键词快捷入口（画图/清记忆）优先处理，其余交给 LLM+Tools 自主决策。
     */
    private void handleText(String userId, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            return;
        }

        // 快捷指令：清除记忆
        if (text.equals("/clear") || text.contains("清除记忆") || text.contains("重新开始")) {
            chatService.clearHistory(userId);
            sendText(userId, "对话记忆已清除，可以重新开始啦~");
            return;
        }

        // 快捷指令：画图（"画 xxx" 或 "/img xxx"）
        if (text.startsWith("画") || text.startsWith("/img") || text.startsWith("/IMG")) {
            String prompt = text.startsWith("画")
                    ? text.substring(1).trim()
                    : text.replaceFirst("(?i)/img\\s*", "").trim();
            if (!prompt.isEmpty()) {
                handleImageIntent(userId, prompt);
                return;
            }
        }

        // 快捷指令：纯语音（"说 xxx" 或 "/voice xxx"）
        if (text.startsWith("说") || text.startsWith("/voice")) {
            String voiceText = text.startsWith("说")
                    ? text.substring(1).trim()
                    : text.replaceFirst("(?i)/voice\\s*", "").trim();
            if (!voiceText.isEmpty()) {
                sendVoiceMp3(userId, voiceText);
                return;
            }
        }

        // 默认：Function Calling 对话（LLM 自主决定调用 get_weather / get_current_time 等工具）
        String reply = chatService.chatWithTools(userId, text);
        sendTextAndVoice(userId, reply, false);
    }

    private void handleImageIntent(String userId, String prompt) {
        sendText(userId, "正在为你生成图片，请稍候...");
        try {
            byte[] imageBytes = imageService.generate(prompt);
            client.sendImage(userId, imageBytes, "generated.png", prompt);
        } catch (Exception e) {
            sendText(userId, "图片生成失败: " + e.getMessage());
        }
    }

    private void handleVoice(String userId, WeixinMessage msg, MessageItem item) throws Exception {
        VoiceItem voice = item.getVoice_item();
        String text = voice.getText();

        if (text == null || text.isBlank()) {
            byte[] audio = client.downloadVoiceFromMessageItem(item);
            try {
                text = asrService.recognize(audio);
            } catch (AsrService.UnsupportedAudioFormatException e) {
                sendText(userId, "抱歉，该语音格式暂无法识别，请发送文字消息。");
                return;
            }
        }

        if (text == null || text.isBlank()) {
            sendText(userId, "没能听清你的语音，请重试或直接发送文字~");
            return;
        }

        log.info("语音识别结果 userId={}: {}", userId, text);

        // 语音输入同样走 Function Calling
        String reply = chatService.chatWithTools(userId, text);
        sendTextAndVoice(userId, reply, true);
    }

    private void handleImage(String userId, MessageItem item) {
        sendText(userId, "正在看图，请稍候...");
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            String reply = visionService.understand(imageBytes, "请描述这张图片的内容。");
            sendTextAndVoice(userId, reply, false);
        } catch (Exception e) {
            log.error("图片理解失败 userId={}: {}", userId, e.getMessage(), e);
            sendText(userId, "图片理解失败: " + e.getMessage());
        }
    }

    /**
     * 双通道并行：文字立即发送，MP3 文件后台合成后异步发送。
     */
    private void sendTextAndVoice(String userId, String text, boolean fromVoice) {
        if (text == null || text.isEmpty()) return;

        sendText(userId, text);
        if (text.length() <= 500) {
            CompletableFuture.runAsync(() -> sendVoiceMp3(userId, text));
        }
    }

    /**
     * 合成并发送 MP3 语音：优先 WebSocket 流式（输出 MP3），失败回退 HTTP API（自动检测格式）。
     */
    private void sendVoiceMp3(String userId, String text) {
        try {
            byte[] audio = ttsService.synthesizeStream(text);
            sendVoiceBytes(userId, audio, text, llmProps.getTts().getFormat());
        } catch (Exception e) {
            log.warn("WebSocket TTS 失败，回退 HTTP API: {}", e.getMessage());
            sendVoiceHttp(userId, text);
        }
    }

    /**
     * HTTP API 语音合成（回退路径），从返回 URL 自动检测格式。
     */
    private void sendVoiceHttp(String userId, String text) {
        try {
            TtsService.TtsResult result = ttsService.synthesize(text);
            sendVoiceBytes(userId, result.audio(), text, result.format());
        } catch (Exception e) {
            log.warn("HTTP 语音合成失败，回退为文字: {}", e.getMessage());
            sendText(userId, text);
        }
    }

    private void sendVoiceBytes(String userId, byte[] audio, String text, String format) {
        try {
            String fileName = "reply." + format;
            String caption = text.length() > 100 ? text.substring(0, 100) + "..." : text;
            client.sendFile(userId, audio, fileName, caption);
            log.info("MP3 文件已发送 userId={} format={} size={}bytes", userId, format, audio.length);
        } catch (IOException e) {
            log.error("发送 MP3 文件失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private void sendText(String userId, String text) {
        try {
            client.sendTextWithTyping(userId, text, 800L);
            log.info("文字已发送 userId={} length={}", userId, text.length());
        } catch (IOException e) {
            log.error("发送文本失败 userId={}: {}", userId, e.getMessage());
        }
    }
}
