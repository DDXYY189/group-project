package com.example.group_demo.controller;

import com.example.group_demo.bot.BotService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bot")
public class BotController {

    private final BotService botService;

    public BotController(BotService botService) {
        this.botService = botService;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loggedIn", botService.isLoggedIn());
        result.put("connectionStatus", botService.getConnectionStatus());
        result.put("llmConfigured", botService.isLlmConfigured());
        result.put("tools", botService.getToolNames());
        result.put("loginError", botService.getLoginError());
        if (botService.getLoginContext() != null) {
            result.put("botId", botService.getLoginContext().getBotId());
            result.put("userId", botService.getLoginContext().getUserId());
        }
        return result;
    }

    @GetMapping(value = "/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng() {
        byte[] png = botService.getQrPng();
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }
}
