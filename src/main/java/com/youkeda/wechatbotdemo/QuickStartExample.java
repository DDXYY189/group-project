package com.youkeda.wechatbotdemo;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;

/**
 * 微信机器人主程序：
 * 1. 二维码扫码登录
 * 2. 循环拉取用户消息
 * 3. 把消息交给通义千问（阿里云百炼）生成回复
 * 4. 自动把回复发回给用户
 */
public class QuickStartExample {

    public static void main(String[] args) throws Exception {
        // 初始化大模型服务（会自动读取 API Key）
        LlmService llmService = new LlmService();

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

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("登录成功，botId = " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("登录失败: " + throwable.getMessage());
                    }
                })
                .build();

        try {
            // 第一步：登录
            String qrCodeContent = client.executeLogin();
            System.out.println("==============================================");
            System.out.println("请把下面的内容生成二维码，用微信扫码登录：");
            System.out.println(qrCodeContent);
            System.out.println("==============================================");

            LoginContext context = client.getLoginFuture().get();
            System.out.println("登录完成，botId = " + context.getBotId());
            System.out.println("机器人已启动，等待用户消息…（Ctrl+C 退出）");

            // 第二步：循环拉取消息 + 自动回复
            while (true) {
                List<WeixinMessage> messages = client.getUpdates();

                for (WeixinMessage msg : messages) {
                    String fromUser = msg.getFrom_user_id();
                    String text = extractText(msg);

                    // 跳过没有文本内容的消息（比如图片、语音等）
                    if (fromUser == null || text == null || text.isBlank()) {
                        continue;
                    }

                    System.out.println("收到 [" + fromUser + "]: " + text);

                    try {
                        // 第三步：调用大模型生成回复
                        String reply = llmService.chat(text);

                        // 第四步：发回给用户
                        client.sendText(fromUser, reply);
                        System.out.println("回复 [" + fromUser + "]: " + reply);
                    } catch (Exception e) {
                        System.err.println("处理消息失败: " + e.getMessage());
                    }
                }
            }
        } finally {
            client.close();
        }
    }

    /** 从消息列表里提取第一条文本内容 */
    private static String extractText(WeixinMessage msg) {
        if (msg.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null && item.getText_item().getText() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }
}
