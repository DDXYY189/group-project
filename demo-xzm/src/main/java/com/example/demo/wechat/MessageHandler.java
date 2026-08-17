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

/**
 * 消息路由：根据消息类型与指令分发到对应的大模型能力，并通过微信回复。
 *
 * 文本指令：
 *   「画 <描述>」/「/img <描述>」 → 通义万相生成图片
 *   「说 <文本>」/「/voice <文本>」 → CosyVoice 语音合成
 *   「/clear」                    → 清除对话记忆
 *   其他                         → Qwen 多轮对话
 * 语音消息：识别为文字 → 对话 → 语音回复
 * 图片消息：确认收到
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

    private void handleText(String userId, String rawText) {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) {
            return;
        }

        String imgPrompt = extractImagePrompt(text);
        if (imgPrompt != null) {
            sendText(userId, "正在为你生成图片，请稍候...");
            try {
                byte[] imageBytes = imageService.generate(imgPrompt);
                client.sendImage(userId, imageBytes, "generated.png", imgPrompt);
            } catch (Exception e) {
                sendText(userId, "图片生成失败: " + e.getMessage());
            }
            return;
        }

        String ttsText = extractTtsText(text);
        if (ttsText != null) {
            sendVoiceReply(userId, ttsText);
            return;
        }

        if (text.equals("/clear") || text.equals("清除记忆")) {
            chatService.clearHistory(userId);
            sendText(userId, "对话记忆已清除，可以重新开始啦~");
            return;
        }

        String reply = chatService.chat(userId, text);
        sendText(userId, reply);
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
        String reply = chatService.chat(userId, text);
        sendVoiceReply(userId, reply);
    }

    /**
     * 处理图片消息：下载图片原图，调用 Qwen-VL 多模态模型理解图片内容并回复。
     */
    private void handleImage(String userId, MessageItem item) {
        sendText(userId, "正在看图，请稍候...");
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            String reply = visionService.understand(imageBytes, "请描述这张图片的内容。");
            sendText(userId, reply);
        } catch (Exception e) {
            log.error("图片理解失败 userId={}: {}", userId, e.getMessage(), e);
            sendText(userId, "图片理解失败: " + e.getMessage());
        }
    }

    private void sendVoiceReply(String userId, String text) {
        try {
            byte[] audio = ttsService.synthesize(text);
            int durationMs = estimateDurationMs(text);
            String ext = llmProps.getTts().getFormat();
            client.sendVoice(userId, audio, "reply." + ext, durationMs, llmProps.getTts().getSampleRate());
        } catch (Exception e) {
            log.warn("语音回复失败，回退为文字: {}", e.getMessage());
            sendText(userId, text);
        }
    }

    private void sendText(String userId, String text) {
        try {
            client.sendTextWithTyping(userId, text, 800L);
        } catch (IOException e) {
            log.error("发送文本失败 userId={}: {}", userId, e.getMessage());
        }
    }

    private int estimateDurationMs(String text) {
        int ms = text.length() * 200;
        return Math.max(1000, Math.min(ms, 60000));
    }

    private String extractImagePrompt(String text) {
        if (text.startsWith("画")) {
            String p = text.substring(1).trim();
            return p.isEmpty() ? null : p;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("/img")) {
            String p = text.substring(4).trim();
            return p.isEmpty() ? null : p;
        }
        return null;
    }

    private String extractTtsText(String text) {
        if (text.startsWith("说")) {
            String p = text.substring(1).trim();
            return p.isEmpty() ? null : p;
        }
        String lower = text.toLowerCase();
        if (lower.startsWith("/voice") || lower.startsWith("/tts")) {
            int idx = text.indexOf(' ');
            if (idx > 0) {
                String p = text.substring(idx + 1).trim();
                return p.isEmpty() ? null : p;
            }
        }
        return null;
    }
}
