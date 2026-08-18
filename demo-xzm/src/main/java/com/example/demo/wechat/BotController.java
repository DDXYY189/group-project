package com.example.demo.wechat;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 机器人状态与登录控制接口。
 */
@RestController
@RequestMapping("/bot")
public class BotController {

    private final WechatBotRunner runner;

    public BotController(WechatBotRunner runner) {
        this.runner = runner;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "loggedIn", runner.isLoggedIn(),
                "botId", runner.getBotId() == null ? "" : runner.getBotId()
        );
    }

    @PostMapping("/login")
    public Map<String, Object> login() {
        if (runner.isLoggedIn()) {
            return Map.of("success", true, "message", "已登录");
        }
        runner.login();
        return Map.of("success", true, "message", "登录流程已启动，请查看控制台二维码");
    }

    @GetMapping(value = "/qrcode", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> qrcode() {
        String qr = runner.getQrContent();
        if (qr == null) {
            return ResponseEntity.ok("<p>二维码尚未生成或已登录。</p>");
        }
        if (qr.startsWith("data:image/")) {
            return ResponseEntity.ok("<html><body style='text-align:center'>"
                    + "<h3>请用微信扫码登录</h3>"
                    + "<img src='" + qr + "' style='width:300px'/>"
                    + "</body></html>");
        }
        String svg = QrCodeUtil.toSvg(qr, 10);
        return ResponseEntity.ok("<html><head><meta charset='UTF-8'></head>"
                + "<body style='text-align:center;background:#f5f5f5'>"
                + "<h3>请用微信扫码登录</h3>"
                + svg
                + "</body></html>");
    }
}
