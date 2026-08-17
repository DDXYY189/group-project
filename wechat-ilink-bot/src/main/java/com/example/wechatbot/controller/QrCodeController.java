package com.example.wechatbot.controller;

import com.example.wechatbot.service.WechatBotService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

@RestController
public class QrCodeController {

    @Autowired
    private WechatBotService botService;

    @GetMapping("/")
    public String loginPage() {
        return "<!DOCTYPE html>\n" +
                "<html lang=\"zh-CN\">\n" +
                "<head>\n" +
                "    <meta charset=\"UTF-8\">\n" +
                "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
                "    <title>微信 iLink Bot 登录</title>\n" +
                "    <style>\n" +
                "        body { font-family: -apple-system, sans-serif; background: #f5f5f5; margin: 0; padding: 40px; display: flex; justify-content: center; }\n" +
                "        .card { background: white; border-radius: 12px; box-shadow: 0 2px 12px rgba(0,0,0,0.1); padding: 40px; text-align: center; max-width: 400px; }\n" +
                "        h1 { color: #07C160; margin-bottom: 8px; }\n" +
                "        p { color: #666; margin: 4px 0; }\n" +
                "        img { margin: 20px 0; border: 1px solid #eee; border-radius: 8px; }\n" +
                "        .status { display: inline-block; padding: 4px 12px; border-radius: 4px; font-size: 14px; margin: 8px 0; }\n" +
                "        .status.running { background: #e8f5e9; color: #2e7d32; }\n" +
                "        .status.stopped { background: #ffebee; color: #c62828; }\n" +
                "        .btn { display: inline-block; padding: 8px 24px; border: none; border-radius: 6px; cursor: pointer; font-size: 14px; margin: 4px; }\n" +
                "        .btn-start { background: #07C160; color: white; }\n" +
                "        .btn-stop { background: #eee; color: #333; }\n" +
                "    </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "    <div class=\"card\">\n" +
                "        <h1>微信 iLink Bot</h1>\n" +
                "        <p>请使用微信扫描下方二维码登录</p>\n" +
                "        <div id=\"status\" class=\"status stopped\">未启动</div>\n" +
                "        <br/>\n" +
                "        <img id=\"qrcode\" src=\"/api/qr-code\" alt=\"二维码\" width=\"256\" height=\"256\"/>\n" +
                "        <br/>\n" +
                "        <button class=\"btn btn-start\" onclick=\"control('start')\">启动 Bot</button>\n" +
                "        <button class=\"btn btn-stop\" onclick=\"control('stop')\">停止 Bot</button>\n" +
                "    </div>\n" +
                "    <script>\n" +
                "        function control(action) {\n" +
                "            fetch('/api/bot/' + action, { method: 'POST' })\n" +
                "                .then(r => r.json())\n" +
                "                .then(d => { location.reload(); });\n" +
                "        }\n" +
                "        function checkStatus() {\n" +
                "            fetch('/api/bot/status')\n" +
                "                .then(r => r.json())\n" +
                "                .then(d => {\n" +
                "                    const el = document.getElementById('status');\n" +
                "                    el.textContent = d.running ? (d.loggedIn ? '已登录' : '等待扫码') : '未启动';\n" +
                "                    el.className = 'status ' + (d.running ? 'running' : 'stopped');\n" +
                "                });\n" +
                "        }\n" +
                "        checkStatus();\n" +
                "        setInterval(checkStatus, 3000);\n" +
                "    </script>\n" +
                "</body>\n" +
                "</html>";
    }

    @GetMapping(value = "/api/qr-code", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrCode() throws Exception {
        String content = botService.getQrCodeContent();
        if (content == null || content.isEmpty()) {
            content = "请先启动 Bot";
        }

        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        hints.put(EncodeHintType.MARGIN, 1);

        BitMatrix bitMatrix = new MultiFormatWriter().encode(
                content, BarcodeFormat.QR_CODE, 256, 256, hints);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(bitMatrix);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", baos);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(baos.toByteArray());
    }

    @GetMapping("/api/bot/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("running", botService.isRunning());
        status.put("loggedIn", botService.isLoggedIn());
        return status;
    }

    @PostMapping("/api/bot/start")
    public Map<String, Object> startBot() {
        botService.start();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Bot 启动中, 请查看控制台日志");
        return result;
    }

    @PostMapping("/api/bot/stop")
    public Map<String, Object> stopBot() {
        botService.stop();
        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "Bot 已停止");
        return result;
    }
}
