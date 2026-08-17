package com.example.wechatbot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    @Autowired
    private LlmService llmService;

    @Value("${wechat.enabled:true}")
    private boolean enabled;

    @Value("${wechat.auto-start:true}")
    private boolean autoStart;

    @Value("${wechat.poll-interval:1000}")
    private long pollInterval;

    private ILinkClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private String qrCodeContent;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("微信 Bot 未启用 (wechat.enabled=false)");
            return;
        }
        if (!autoStart) {
            log.info("微信 Bot 未自动启动, 请访问 http://localhost:8080/ 手动启动");
            return;
        }
        new Thread(this::start, "wechat-bot-start").start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public void start() {
        if (running.get()) {
            log.warn("Bot 已在运行中");
            return;
        }
        running.set(true);
        log.info("========== 微信 Bot 启动中 ==========");

        try {
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .writeTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .retryBaseDelayMs(1000)
                    .retryMaxDelayMs(10000)
                    .heartbeatEnabled(true)
                    .heartbeatIntervalMs(30000)
                    .channelVersion("1.0.0")
                    .build();

            client = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("登录成功! botId = {}", context.getBotId());
                            loggedIn.set(true);
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("登录失败: {}", throwable.getMessage(), throwable);
                            loggedIn.set(false);
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            handleMessages(messages);
                        }
                    })
                    .onDisconnect(new OnDisconnectListener() {
                        @Override
                        public void onDisconnect(Throwable throwable) {
                            log.warn("连接断开: {}", throwable.getMessage());
                            loggedIn.set(false);
                        }

                        @Override
                        public void onReconnectStart(int attempt) {
                            log.info("正在尝试第 {} 次重连...", attempt);
                        }

                        @Override
                        public void onReconnectSuccess() {
                            log.info("重连成功, 恢复登录状态");
                            loggedIn.set(true);
                        }

                        @Override
                        public void onReconnectFailed(Throwable throwable) {
                            log.error("重连失败: {}", throwable.getMessage(), throwable);
                            loggedIn.set(false);
                        }
                    })
                    .onHeartbeat(new OnHeartbeatListener() {
                        @Override
                        public void onHeartbeatSuccess() {
                            log.debug("心跳正常");
                        }

                        @Override
                        public void onHeartbeatFailure(Throwable throwable) {
                            log.warn("心跳失败: {}", throwable.getMessage());
                        }
                    })
                    .build();

            qrCodeContent = client.executeLogin();
            log.info("二维码已生成, 请访问 http://localhost:8080/ 扫码登录");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成, botId = {}", context.getBotId());

            startMessageLoop();

        } catch (Exception e) {
            log.error("Bot 启动失败: {}", e.getMessage(), e);
            running.set(false);
        }
    }

    public void stop() {
        running.set(false);
        loggedIn.set(false);
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭客户端异常: {}", e.getMessage());
            }
        }
        log.info("微信 Bot 已停止");
    }

    public String getQrCodeContent() {
        return qrCodeContent;
    }

    public boolean isLoggedIn() {
        return loggedIn.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void startMessageLoop() {
        Thread pollThread = new Thread(() -> {
            log.info("消息轮询循环已启动");
            while (running.get() && loggedIn.get()) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null && !messages.isEmpty()) {
                        log.info("收到 {} 条消息", messages.size());
                    }
                } catch (Exception e) {
                    log.error("消息轮询异常: {}", e.getMessage(), e);
                    try {
                        Thread.sleep(pollInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("消息轮询循环已停止");
        }, "wechat-message-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage msg : messages) {
            try {
                handleSingleMessage(msg);
            } catch (Exception e) {
                log.error("处理消息异常: {}", e.getMessage(), e);
            }
        }
    }

    private void handleSingleMessage(WeixinMessage msg) throws java.io.IOException {
        String fromUserId = msg.getFrom_user_id();
        log.info("收到消息: fromUserId={}", fromUserId);

        if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
            return;
        }

        for (MessageItem item : msg.getItem_list()) {

            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                log.info("文本消息: {}", text);

                String reply = llmService.chat(text);
                client.sendTextWithTyping(fromUserId, reply, 1500L);
                continue;
            }

            if (item.getImage_item() != null) {
                log.info("收到图片消息, 下载并分析");

                byte[] imageBytes = client.downloadImageFromMessageItem(item);
                String reply = llmService.chatWithImage(imageBytes, "请描述这张图片的内容");
                client.sendText(fromUserId, reply);
                continue;
            }

            if (item.getVoice_item() != null) {
                log.info("收到语音消息, 下载并识别");

                byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
                String reply = llmService.chatWithAudio(voiceBytes);
                client.sendText(fromUserId, reply);
                continue;
            }

            if (item.getVideo_item() != null) {
                log.info("收到视频消息 (暂不支持)");
                client.sendText(fromUserId, "收到视频消息, 暂不支持视频理解功能");
                continue;
            }

            if (item.getFile_item() != null) {
                log.info("收到文件消息 (暂不支持)");
                client.sendText(fromUserId, "收到文件消息, 暂不支持文件解析功能");
                continue;
            }
        }
    }
}
