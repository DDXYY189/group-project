package com.example.demo_wkx.service;

import org.springframework.stereotype.Service;

/**
 * 机器人状态服务
 * 存储 iLink 微信登录二维码和登录状态，供 BotController REST 接口暴露给前端。
 */
@Service
public class BotStateService {

    private volatile byte[] qrCodeBytes;
    private volatile boolean loggedIn;
    private volatile String botId = "";
    private volatile String userId = "";

    public byte[] getQrCodeBytes() { return qrCodeBytes; }
    public void setQrCodeBytes(byte[] bytes) { this.qrCodeBytes = bytes; }

    public boolean isLoggedIn() { return loggedIn; }
    public void setLoggedIn(boolean loggedIn) { this.loggedIn = loggedIn; }

    public String getBotId() { return botId; }
    public void setBotId(String botId) { this.botId = botId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
