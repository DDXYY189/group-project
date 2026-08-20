package com.example.group_demo.controller;

import com.example.group_demo.bot.BotService;
import com.example.group_demo.llm.ConversationMemoryService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/bot")
public class BotController {

    private final BotService botService;
    private final ConversationMemoryService memoryService;

    public BotController(BotService botService, ConversationMemoryService memoryService) {
        this.botService = botService;
        this.memoryService = memoryService;
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

    @PostMapping("/relogin")
    public ResponseEntity<Void> relogin() {
        botService.startLogin();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/memory/clear")
    public Map<String, Object> clearMemory() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", true);
        result.put("deleted", memoryService.clearAll());
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
