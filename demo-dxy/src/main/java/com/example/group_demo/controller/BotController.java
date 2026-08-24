package com.example.group_demo.controller;

import com.example.group_demo.bot.BotService;
import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.router.MessageRouter;
import com.example.group_demo.search.SearchService;
import com.example.group_demo.session.BotSessionManager;
import com.example.group_demo.skill.SkillRegistry;
import com.example.group_demo.tool.TodoService;
import com.example.group_demo.tool.chain.ToolChainService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    private final BotSessionManager sessionManager;
    private final ConversationMemoryService memoryService;
    private final TodoService todoService;
    private final SearchService searchService;
    private final ToolChainService toolChainService;
    private final SkillRegistry skillRegistry;
    private final MessageRouter messageRouter;
    private final KeywordRagService ragService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BotController(BotSessionManager sessionManager, ConversationMemoryService memoryService,
                         TodoService todoService, SearchService searchService,
                         ToolChainService toolChainService, SkillRegistry skillRegistry,
                         MessageRouter messageRouter, KeywordRagService ragService) {
        this.sessionManager = sessionManager;
        this.memoryService = memoryService;
        this.todoService = todoService;
        this.searchService = searchService;
        this.toolChainService = toolChainService;
        this.skillRegistry = skillRegistry;
        this.messageRouter = messageRouter;
        this.ragService = ragService;
    }

    @PostMapping("/session")
    public Map<String, Object> createSession() {
        BotService bot = sessionManager.createSession();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", bot.getSessionId());
        return result;
    }

    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return sessionManager.all().stream().map(bot -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("sessionId", bot.getSessionId());
            item.put("loggedIn", bot.isLoggedIn());
            LoginContext context = bot.getLoginContext();
            if (context != null) {
                item.put("botId", context.getBotId());
                item.put("userId", context.getUserId());
            }
            return item;
        }).toList();
    }

    @GetMapping("/session/{sessionId}/status")
    public ResponseEntity<Map<String, Object>> status(@PathVariable String sessionId) {
        BotService bot = sessionManager.get(sessionId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", bot.getSessionId());
        result.put("loggedIn", bot.isLoggedIn());
        result.put("connectionStatus", bot.getConnectionStatus());
        result.put("llmConfigured", bot.isLlmConfigured());
        result.put("tools", bot.getToolNames());
        result.put("loginError", bot.getLoginError());
        LoginContext context = bot.getLoginContext();
        if (context != null) {
            result.put("botId", context.getBotId());
            result.put("userId", context.getUserId());
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/session/{sessionId}/relogin")
    public ResponseEntity<Void> relogin(@PathVariable String sessionId) {
        if (!sessionManager.relogin(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.accepted().build();
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        if (!sessionManager.remove(sessionId)) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/session/{sessionId}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qrPng(@PathVariable String sessionId) {
        BotService bot = sessionManager.get(sessionId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        byte[] png = bot.getQrPng();
        if (png == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(png);
    }

    @PostMapping("/session/{sessionId}/memory/clear")
    public ResponseEntity<Map<String, Object>> clearMemory(@PathVariable String sessionId) {
        BotService bot = sessionManager.get(sessionId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", true);
        result.put("deleted", memoryService.clearAll());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/session/{sessionId}/todo/clear")
    public ResponseEntity<Map<String, Object>> clearTodo(@PathVariable String sessionId) {
        BotService bot = sessionManager.get(sessionId);
        if (bot == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cleared", true);
        result.put("deleted", todoService.clearAll());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tool-chains")
    public List<Map<String, Object>> toolChains() {
        return toolChainService.summaries();
    }

    @GetMapping("/skills")
    public List<Map<String, Object>> skills() {
        return skillRegistry.summaries();
    }

    @GetMapping("/route")
    public Map<String, Object> route(@RequestParam("q") String text,
                                     @RequestParam(defaultValue = "demo") String userId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("input", text);
        result.put("userId", userId);
        try {
            result.put("reply", messageRouter.route(userId, text));
        } catch (Exception e) {
            result.put("error", e.getMessage());
        }
        return result;
    }

    @GetMapping("/rag/status")
    public Map<String, Object> ragStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", ragService.isEnabled());
        result.put("chunks", ragService.chunkCount());
        result.put("topK", ragService.topK());
        return result;
    }

    @PostMapping("/rag/toggle")
    public Map<String, Object> toggleRag(@RequestParam(defaultValue = "true") boolean enabled) {
        ragService.setEnabled(enabled);
        return ragStatus();
    }

    @GetMapping("/rag/search")
    public Map<String, Object> ragSearch(@RequestParam("q") String query) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("hits", ragService.retrieve(query).stream().map(KnowledgeChunk::toMap).toList());
        return result;
    }

    @GetMapping("/search")
    public Map<String, Object> search(@RequestParam("q") String query,
                                      @RequestParam(defaultValue = "8") int maxResults) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("query", query);
        result.put("maxResults", maxResults);
        result.put("result", searchService.search(query, maxResults));
        return result;
    }

    @PostMapping("/tool-chains/{chainId}/run")
    public Map<String, Object> runToolChain(@PathVariable String chainId,
                                            @RequestParam(defaultValue = "demo") String userId,
                                            @RequestBody(required = false) Map<String, Object> arguments) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chainId", chainId);
        result.put("userId", userId);
        JsonNode chainInput = arguments == null ? null : objectMapper.valueToTree(arguments);
        result.put("result", toolChainService.run(userId, chainId, chainInput));
        return result;
    }
}
