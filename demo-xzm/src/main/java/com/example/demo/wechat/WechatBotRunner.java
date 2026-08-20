package com.example.demo.wechat;

import com.example.demo.config.WechatBotProperties;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.exception.NotLoginException;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/**
 * 微信机器人启动器：启动时执行扫码登录，登录成功后循环拉取消息并分发处理。
 */
@Component
public class WechatBotRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WechatBotRunner.class);

    private final ILinkClient client;
    private final MessageHandler messageHandler;
    private final WechatBotProperties props;

    private volatile String qrContent;
    private volatile boolean loggedIn;
    private volatile String botId;
    private volatile boolean running = true;
    private Thread pollThread;

    public WechatBotRunner(ILinkClient client, MessageHandler messageHandler, WechatBotProperties props) {
        this.client = client;
        this.messageHandler = messageHandler;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.isAutoLogin()) {
            log.info("wechat.bot.auto-login=false，跳过自动登录。可调用 /bot/login 手动触发。");
            return;
        }
        pollThread = new Thread(this::loginAndPoll, "wechat-bot-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public synchronized void login() {
        if (pollThread != null && pollThread.isAlive()) {
            pollThread.interrupt();
        }
        // 重置登录状态，支持掉线后重新扫码登录（SDK 不支持自动重连，需手动重新登录）
        this.loggedIn = false;
        this.qrContent = null;
        pollThread = new Thread(this::loginAndPoll, "wechat-bot-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void loginAndPoll() {
        try {
            String qr = client.executeLogin();
            this.qrContent = qr;
            printQr(qr);

            LoginContext ctx = client.getLoginFuture().get();
            this.loggedIn = true;
            this.botId = ctx.getBotId();
            this.qrContent = null;
            log.info("微信登录成功 botId={}，开始接收消息", ctx.getBotId());

            pollLoop();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("微信登录线程被中断");
        } catch (Exception e) {
            log.error("微信登录/轮询失败", e);
        }
    }

    private void printQr(String qr) {
        if (qr == null || qr.isBlank()) {
            log.warn("未获取到二维码内容");
            return;
        }
        if (qr.startsWith("data:image/")) {
            saveQrImage(qr);
        } else {
            System.out.println();
            System.out.println("========== 请用微信扫描下方二维码登录 ==========");
            System.out.println(QrCodeUtil.toAscii(qr));
            System.out.println("==============================================");
            System.out.println("或在浏览器访问 http://localhost:8080/bot/qrcode 查看二维码");
        }
    }

    private void saveQrImage(String dataUri) {
        try {
            int comma = dataUri.indexOf(',');
            String base64 = comma > 0 ? dataUri.substring(comma + 1) : dataUri;
            byte[] image = Base64.getDecoder().decode(base64);
            Path out = Path.of("qrcode.png");
            Files.write(out, image);
            log.info("二维码图片已保存至 {}，请打开扫码登录", out.toAbsolutePath());
        } catch (Exception e) {
            log.warn("保存二维码图片失败: {}", e.getMessage());
        }
    }

    private void pollLoop() {
        int consecutiveErrors = 0;
        while (running) {
            try {
                List<WeixinMessage> messages = client.getUpdates();
                consecutiveErrors = 0;
                if (messages != null && !messages.isEmpty()) {
                    for (WeixinMessage msg : messages) {
                        try {
                            messageHandler.handle(msg);
                        } catch (Exception e) {
                            log.error("处理消息异常: {}", e.getMessage(), e);
                        }
                    }
                }
                Thread.sleep(props.getPollIntervalMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (NotLoginException e) {
                // 会话已掉线（登录态失效），SDK 不支持自动重连，需手动重新扫码
                this.loggedIn = false;
                this.botId = null;
                log.error("微信登录态已失效（掉线），请重新扫码登录：POST http://localhost:8080/bot/login 或重启程序");
                break;
            } catch (Exception e) {
                consecutiveErrors++;
                log.warn("拉取消息异常({}次): {}，3秒后重试", consecutiveErrors, e.getMessage());
                if (consecutiveErrors >= 10) {
                    this.loggedIn = false;
                    log.error("连续拉取失败 {} 次，判定为掉线，请重新扫码登录", consecutiveErrors);
                    break;
                }
                sleepQuietly(3000);
            }
        }
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (pollThread != null) {
            pollThread.interrupt();
        }
        log.info("微信机器人已停止");
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getBotId() {
        return botId;
    }

    public String getQrContent() {
        return qrContent;
    }
}
