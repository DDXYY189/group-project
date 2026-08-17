package com.example.group_demo.bot;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.voice.VoiceService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class BotService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final ILinkClient client;
    private final LlmService llmService;
    private final VoiceService voiceService;
    private final ExecutorService messageExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ilink-message-handler");
        thread.setDaemon(true);
        return thread;
    });

    private volatile byte[] qrPng;
    private volatile String loginError;
    private volatile LoginContext loginContext;

    public BotService(@Lazy ILinkClient client, LlmService llmService, VoiceService voiceService) {
        this.client = client;
        this.llmService = llmService;
        this.voiceService = voiceService;
    }

    @Override
    public void run(ApplicationArguments args) {
        startLogin();
    }

    public void startLogin() {
        try {
            String qrContent = client.executeLogin();
            this.qrPng = buildQrPng(qrContent);
            log.info("二维码已就绪，访问 http://localhost:8080/api/bot/qr.png 扫码登录");
        } catch (Exception e) {
            this.loginError = e.getMessage();
            log.error("获取登录二维码失败", e);
        }
    }

    public void onLoginSuccess(LoginContext context) {
        this.loginContext = context;
        log.info("登录成功 botId={} userId={}", context.getBotId(), context.getUserId());
    }

    public void onLoginFailure(Throwable throwable) {
        this.loginError = throwable.getMessage();
        log.error("登录失败: {}", throwable.getMessage());
    }

    public void onMessages(List<WeixinMessage> messages) {
        messageExecutor.execute(() -> handleMessages(messages));
    }

    private void handleMessages(List<WeixinMessage> messages) {
        for (WeixinMessage message : messages) {
            String fromUserId = message.getFrom_user_id();
            if (fromUserId == null) {
                log.info("收到无发送人的消息 messageId={}", message.getMessage_id());
                continue;
            }
            if (isSelfMessage(message)) {
                log.info("跳过机器人自身消息 from={}", fromUserId);
                continue;
            }
            List<MessageItem> items = message.getItem_list();
            if (items == null || items.isEmpty()) {
                log.info("收到无内容的消息 from={} messageId={}", fromUserId, message.getMessage_id());
                continue;
            }
            log.info("收到消息 from={} itemCount={} messageId={}",
                fromUserId, items.size(), message.getMessage_id());
            for (MessageItem item : items) {
                String reply = buildReply(item);
                if (reply == null) {
                    continue;
                }
                try {
                    client.sendTextWithTyping(fromUserId, reply, 800L);
                    log.info("已回复 from={} reply={}", fromUserId, reply);
                } catch (Exception e) {
                    log.error("回复消息失败 fromUserId={}", fromUserId, e);
                }
            }
        }
    }

    private boolean isSelfMessage(WeixinMessage message) {
        LoginContext context = loginContext;
        if (context == null) {
            return false;
        }
        String from = message.getFrom_user_id();
        return from != null && from.equals(context.getBotId());
    }

    private String buildReply(MessageItem item) {
        TextItem textItem = item.getText_item();
        if (textItem != null && textItem.getText() != null) {
            return replyToText(textItem.getText());
        }
        if (item.getImage_item() != null) {
            return replyToImage(item);
        }
        if (item.getVoice_item() != null) {
            return replyToVoice(item);
        }
        return null;
    }

    private String replyToVoice(MessageItem item) {
        VoiceItem voiceItem = item.getVoice_item();
        try {
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
            log.info("收到语音 encodeType={} sampleRate={} playtime={} size={} header={}",
                voiceItem.getEncode_type(), voiceItem.getSample_rate(), voiceItem.getPlaytime(),
                voiceBytes.length, toHex(voiceBytes));

            String transcript = null;
            String serverText = voiceItem.getText();
            if (serverText != null && !serverText.isBlank()) {
                transcript = serverText.trim();
                log.info("使用服务端语音转写结果");
            } else {
                byte[] wavBytes = voiceService.toWav(voiceBytes, detectAudioSuffix(voiceBytes));
                transcript = voiceService.transcribe(wavBytes);
            }

            if (transcript == null || transcript.isBlank()) {
                return "没有听清你说的内容，请再发一次语音或直接打字。";
            }
            log.info("语音转写结果 text={}", transcript);
            return replyToText("用户发来语音，内容为：" + transcript + "。请直接回答用户这句话。");
        } catch (Exception e) {
            log.warn("语音消息处理失败：{}", e.getMessage());
            return "语音处理失败：" + e.getMessage();
        }
    }

    private String detectAudioSuffix(byte[] bytes) {
        if (bytes.length >= 8 && new String(bytes, 0, 8, StandardCharsets.US_ASCII).startsWith("#!AMR")) {
            return ".amr";
        }
        if (bytes.length >= 9 && new String(bytes, 0, 9, StandardCharsets.US_ASCII).startsWith("#!SILK_V3")) {
            return ".silk";
        }
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return ".wav";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return ".mp3";
        }
        return ".silk";
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(16, bytes.length);
        for (int i = 0; i < count; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private String replyToText(String userText) {
        if (llmService.isConfigured()) {
            try {
                return llmService.chat(userText);
            } catch (Exception e) {
                log.warn("LLM 文本调用失败，回退为回显：{}", e.getMessage());
            }
        }
        return "收到：" + userText;
    }

    private String replyToImage(MessageItem item) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            return llmService.chatWithImage("请描述这张图片", imageBytes, "image.png");
        } catch (Exception e) {
            log.warn("图片消息处理失败：{}", e.getMessage());
            return "图片解析失败：" + e.getMessage();
        }
    }

    private byte[] buildQrPng(String qrContent) throws IOException, WriterException {
        String content = qrContent == null ? "" : qrContent.trim();
        if (content.startsWith("data:image")) {
            int commaIndex = content.indexOf(',');
            String base64 = commaIndex >= 0 ? content.substring(commaIndex + 1) : content;
            return Base64.getDecoder().decode(base64);
        }
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    public byte[] getQrPng() {
        return qrPng;
    }

    public boolean isLoggedIn() {
        return client.isLoggedIn();
    }

    public String getConnectionStatus() {
        return String.valueOf(client.getConnectionStatus());
    }

    public LoginContext getLoginContext() {
        return loginContext;
    }

    public boolean isLlmConfigured() {
        return llmService.isConfigured();
    }

    public String getLoginError() {
        return loginError;
    }
}
