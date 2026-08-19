package com.example.demo.web;

import com.example.demo.wechat.WechatBotService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
public class QrController {

    private final WechatBotService bot;

    public QrController(WechatBotService bot) {
        this.bot = bot;
    }

    @GetMapping(value = "/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng() throws Exception {
        String content = bot.getQrContent();
        if (content == null || content.isBlank()) {
            return ResponseEntity.notFound().build();
        }

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 2);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);

        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 400, 400, hints);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(out.toByteArray());
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String page() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                    <meta charset="utf-8">
                    <meta http-equiv="refresh" content="3">
                    <title>微信机器人登录</title>
                    <style>
                        body { font-family: system-ui, sans-serif; text-align: center; padding: 40px; }
                        img { border: 1px solid #ddd; border-radius: 8px; margin-top: 20px; }
                    </style>
                </head>
                <body>
                    <h1>请用微信扫码登录</h1>
                    <p>二维码每 3 秒自动刷新，扫码后即可在微信中与机器人对话。</p>
                    <img src="/qr.png" alt="微信登录二维码" width="400" height="400">
                </body>
                </html>
                """;
    }
}
