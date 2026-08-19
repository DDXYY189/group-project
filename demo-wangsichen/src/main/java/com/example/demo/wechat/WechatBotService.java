package com.example.demo.wechat;

import com.example.demo.agent.AgentResponse;
import com.example.demo.agent.AgentService;
import com.example.demo.image.ImageService;
import com.example.demo.voice.VoiceResult;
import com.example.demo.voice.VoiceService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.List;

@Component
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    private final AgentService agentService;
    private final VoiceService voiceService;
    private final ImageService imageService;
    private final WechatVoiceSender voiceSender;
    private final Thread worker;

    private volatile boolean running = true;
    private volatile ILinkClient client;
    private volatile String qrContent;
    private volatile boolean loggedIn = false;

    public WechatBotService(
            AgentService agentService,
            VoiceService voiceService,
            ImageService imageService,
            WechatVoiceSender voiceSender) {
        this.agentService = agentService;
        this.voiceService = voiceService;
        this.imageService = imageService;
        this.voiceSender = voiceSender;
        this.worker = new Thread(this::run, "wechat-ilink-worker");
        this.worker.setDaemon(true);
    }

    @PostConstruct
    public void start() {
        worker.start();
    }

    private void run() {
        ILinkClient local = null;
        try {
            local = ILinkClient.builder()
                    .config(ILinkConfig.builder()
                            .connectTimeoutMs(35000)
                            .readTimeoutMs(35000)
                            .writeTimeoutMs(35000)
                            .httpMaxRetries(3)
                            .retryBaseDelayMs(1000)
                            .retryMaxDelayMs(10000)
                            .heartbeatEnabled(false)
                            .heartbeatIntervalMs(30000)
                            .channelVersion("1.0.0")
                            .build())
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            loggedIn = true;
                            log.info("微信登录成功，botId={}", context.getBotId());
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("微信登录失败", throwable);
                        }
                    })
                    .build();
            this.client = local;

            qrContent = local.executeLogin();
            log.info("请打开 http://localhost:8080/ 扫码登录");

            LoginContext context = local.getLoginFuture().get();
            loggedIn = true;
            log.info("登录完成，botId={}", context.getBotId());

            while (running) {
                try {
                    List<WeixinMessage> messages = local.getUpdates();
                    handleMessages(messages);
                } catch (IOException | RuntimeException e) {
                    if (!running) {
                        break;
                    }
                    log.warn("拉取消息失败，稍后重试：{}", e.getMessage());
                    sleepQuietly(3000);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.error("微信机器人运行异常", e);
        } finally {
            if (local != null) {
                try {
                    local.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        for (WeixinMessage message : messages) {
            String userId = message.getFrom_user_id();
            if (userId != null && userId.endsWith("@im.bot")) {
                continue;
            }

            InboundMessage inbound = extract(message);
            if (inbound == null || !StringUtils.hasText(inbound.text())) {
                continue;
            }

            log.info("收到来自 {} 的{}消息：{}",
                    userId, inbound.voice() ? "语音" : "文字", inbound.text());

            AgentResponse response = agentService.handle(inbound.text().trim());
            execute(inbound, response);
        }
    }

    private InboundMessage extract(WeixinMessage message) {
        String userId = message.getFrom_user_id();
        String contextToken = message.getContext_token();
        if (message.getItem_list() == null) {
            return null;
        }

        for (MessageItem item : message.getItem_list()) {
            if (item.getText_item() != null) {
                Object rawText = item.getText_item().getText();
                if (rawText != null && StringUtils.hasText(rawText.toString())) {
                    return new InboundMessage(userId, contextToken, rawText.toString(), false);
                }
            }

            if (item.getVoice_item() != null
                    && StringUtils.hasText(item.getVoice_item().getText())) {
                var vi = item.getVoice_item();
                log.info(
                        "收到语音详情: encode_type={}, sample_rate={}, bits_per_sample={}, playtime={}, 转写文本={}",
                        vi.getEncode_type(),
                        vi.getSample_rate(),
                        vi.getBits_per_sample(),
                        vi.getPlaytime(),
                        vi.getText());
                logInboundVoiceHeader(item);
                return new InboundMessage(
                        userId, contextToken, vi.getText(), true);
            }
        }
        return null;
    }

    private void logInboundVoiceHeader(MessageItem item) {
        try {
            ILinkClient current = this.client;
            if (current == null) {
                return;
            }
            byte[] silk = current.downloadVoiceFromMessageItem(item);
            if (silk == null || silk.length == 0) {
                return;
            }
            StringBuilder hex = new StringBuilder();
            int n = Math.min(16, silk.length);
            for (int i = 0; i < n; i++) {
                hex.append(String.format("%02X ", silk[i]));
            }
            log.info("入站语音 SILK 头（总 {} 字节）：{}", silk.length, hex.toString().trim());
        } catch (Exception e) {
            log.warn("下载入站语音用于诊断失败：{}", e.getMessage());
        }
    }

    private void execute(InboundMessage inbound, AgentResponse response) {
        String intent = response.intent() == null ? "text" : response.intent();
        String content = StringUtils.hasText(response.content()) ? response.content() : "（空回复）";

        switch (intent) {
            case "voice" -> sendVoice(inbound.userId(), inbound.contextToken(), content);
            case "image" -> sendImage(inbound.userId(), content);
            default -> sendText(inbound.userId(), content);
        }
    }

    private void sendText(String userId, String text) {
        try {
            ILinkClient current = this.client;
            if (current != null && loggedIn) {
                current.sendText(userId, text);
            }
        } catch (IOException e) {
            log.error("回复文字消息失败", e);
        }
    }

    private void sendVoice(String userId, String contextToken, String text) {
        log.info("开始为 {} 合成语音，文本：{}", userId, text);
        VoiceResult voice = voiceService.textToSilk(text);
        if (voice == null || voice.silkBytes() == null || voice.silkBytes().length == 0) {
            sendText(userId, "语音合成失败，先用文字回复：\n" + text);
            return;
        }

        log.info("语音合成完成，字节数={}，时长={}ms，采样率={}",
                voice.silkBytes().length, voice.durationMs(), voice.sampleRate());

        try {
            ILinkClient current = this.client;
            if (current != null && loggedIn) {
                LoginContext login = current.getLoginContext();
                if (login == null || !StringUtils.hasText(contextToken)) {
                    sendText(userId, "语音发送失败（缺少会话上下文），先用文字回复：\n" + text);
                    return;
                }
                voiceSender.send(
                        login, userId, contextToken, voice.silkBytes(), voice.durationMs());
                log.info("语音消息已发送给 {}", userId);
            }
        } catch (Exception e) {
            log.error("回复语音消息失败", e);
            sendText(userId, "语音发送失败，先用文字回复：\n" + text);
        }

        if (voice.mp3Bytes() != null && voice.mp3Bytes().length > 0) {
            try {
                ILinkClient current = this.client;
                if (current != null && loggedIn) {
                    current.sendFile(userId, voice.mp3Bytes(), "语音回复.mp3", null);
                    log.info("语音 MP3 文件已发送给 {}", userId);
                }
            } catch (IOException e) {
                log.error("回复语音 MP3 文件失败", e);
            }
        }
    }

    private void sendImage(String userId, String prompt) {
        byte[] imageBytes = imageService.generateImage(prompt);
        if (imageBytes == null || imageBytes.length == 0) {
            sendText(userId, "图片生成失败（可能未配置图片生成 API Key），先用文字回复：\n" + prompt);
            return;
        }

        try {
            ILinkClient current = this.client;
            if (current != null && loggedIn) {
                current.sendImage(userId, imageBytes, "image.png", null);
            }
        } catch (IOException e) {
            log.error("回复图片消息失败", e);
            sendText(userId, "图片生成成功，但发送失败：" + e.getMessage());
        }
    }

    public String getQrContent() {
        return qrContent;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    @PreDestroy
    public void stop() {
        running = false;
        worker.interrupt();
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
