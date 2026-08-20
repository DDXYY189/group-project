package com.example.group_demo.controller;

import com.example.group_demo.bot.BotService;
import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.tool.chain.ToolChainService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/bot")
public class BotController {

    private final BotService botService;
    private final ConversationMemoryService memoryService;
    private final ToolChainService toolChainService;

    public BotController(BotService botService, ConversationMemoryService memoryService,
                         ToolChainService toolChainService) {
        this.botService = botService;
        this.memoryService = memoryService;
        this.toolChainService = toolChainService;
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

    @GetMapping("/tool-chains")
    public List<Map<String, Object>> toolChains() {
        return toolChainService.summaries();
    }

    @PostMapping("/tool-chains/{chainId}/run")
    public Map<String, Object> runToolChain(@PathVariable String chainId,
                                            @RequestParam(defaultValue = "demo") String userId,
                                            @RequestBody(required = false) JsonNode arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chainId);
        result.put("userId", userId);
        result.put("result", toolChainService.run(userId, chainId, arguments));
        return result;
    }
}
