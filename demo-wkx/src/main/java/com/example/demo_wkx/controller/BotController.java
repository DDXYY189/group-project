package com.example.demo_wkx.controller;

import com.example.demo_wkx.service.BotStateService;
import com.example.demo_wkx.service.LlmService;
import com.example.demo_wkx.skill.SkillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 微信机器人 REST 控制器
 * 仿照 demo-dxy 的 BotController，暴露二维码、状态和能力接口。
 * 前端控制台 (Flask dashboard) 通过此接口获取真实微信登录二维码。
 */
@RestController
@RequestMapping("/api/bot")
public class BotController {

    @Autowired
    private BotStateService botStateService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> map = new HashMap<>();
        map.put("loggedIn", botStateService.isLoggedIn());
        map.put("connectionStatus", botStateService.isLoggedIn() ? "CONNECTED" : "DISCONNECTED");
        map.put("llmConfigured", true);
        map.put("loginError", null);
        map.put("botId", botStateService.getBotId());
        map.put("userId", botStateService.getUserId());
        map.put("tools", java.util.List.of(
                "outfit_consultation", "product_search", "price_comparison",
                "cart_management", "weather_query", "body_assessment",
                "get_weather", "get_current_time", "get_fashion_advice",
                "zodiac_fortune"
        ));
        map.put("fashionApiUrl", "http://localhost:5000");
        return map;
    }

    @GetMapping(value = "/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrCode() {
        byte[] bytes = botStateService.getQrCodeBytes();
        if (bytes == null || bytes.length == 0) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.IMAGE_PNG)
                .body(bytes);
    }

    @PostMapping("/memory/clear")
    public Map<String, Object> clearMemory() {
        return Map.of("success", true, "deleted", 0);
    }
}
