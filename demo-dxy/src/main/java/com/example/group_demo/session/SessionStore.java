package com.example.group_demo.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.context.ConversationContext;
import com.github.wechat.ilink.sdk.core.context.ContextKey;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SessionStore {

    private static final Logger log = LoggerFactory.getLogger(SessionStore.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SessionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        createSchema();
    }

    private void createSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS bot_session (
              session_id VARCHAR(64) PRIMARY KEY,
              bot_token VARCHAR(512),
              user_id VARCHAR(128),
              bot_id VARCHAR(128),
              base_url VARCHAR(512),
              updates_cursor VARCHAR(512),
              contexts_json CLOB,
              updated_at BIGINT NOT NULL
            )
            """);
    }

    public void save(String sessionId, ResumeContext resume) {
        if (resume == null || resume.getLoginContext() == null) {
            return;
        }
        LoginContext login = resume.getLoginContext();
        jdbcTemplate.update("""
            MERGE INTO bot_session
              (session_id, bot_token, user_id, bot_id, base_url, updates_cursor, contexts_json, updated_at)
              KEY(session_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            sessionId,
            login.getBotToken(),
            login.getUserId(),
            login.getBotId(),
            login.getBaseUrl(),
            resume.getUpdatesCursor(),
            toJson(sessionId, resume.getConversationContextMap()),
            System.currentTimeMillis()
        );
    }

    public List<StoredSession> loadAll() {
        return jdbcTemplate.query("""
            SELECT session_id, bot_token, user_id, bot_id, base_url, updates_cursor, contexts_json
            FROM bot_session
            ORDER BY updated_at
            """,
            (rs, rowNum) -> new StoredSession(
                rs.getString("session_id"),
                rs.getString("bot_token"),
                rs.getString("user_id"),
                rs.getString("bot_id"),
                rs.getString("base_url"),
                rs.getString("updates_cursor"),
                rs.getString("contexts_json")
            )
        );
    }

    public void delete(String sessionId) {
        jdbcTemplate.update("DELETE FROM bot_session WHERE session_id = ?", sessionId);
    }

    public ResumeContext toResumeContext(StoredSession stored) {
        if (stored == null || stored.botToken() == null || stored.userId() == null) {
            return null;
        }
        LoginContext login = new LoginContext(
            stored.botToken(), stored.userId(), stored.botId(), stored.baseUrl());
        return ResumeContext.builder(login)
            .updatesCursor(stored.updatesCursor())
            .conversationContexts(parseContexts(stored.contextsJson()))
            .build();
    }

    private String toJson(String sessionId, Map<String, ConversationContext> contexts) {
        if (contexts == null || contexts.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (ConversationContext ctx : contexts.values()) {
            ContextKey key = ctx.getKey();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("botId", key.getBotId());
            item.put("userId", key.getUserId());
            item.put("latestContextToken", ctx.getLatestContextToken());
            item.put("typingTicket", ctx.getTypingTicket());
            items.add(item);
        }
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            log.warn("保存会话上下文失败 sessionId={}", sessionId, e);
            return "[]";
        }
    }

    private Map<String, ConversationContext> parseContexts(String json) {
        Map<String, ConversationContext> contexts = new LinkedHashMap<>();
        if (json == null || json.isBlank()) {
            return contexts;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isArray()) {
                return contexts;
            }
            for (JsonNode node : root) {
                String botId = node.path("botId").asText(null);
                String userId = node.path("userId").asText(null);
                if (botId == null || userId == null) {
                    continue;
                }
                ConversationContext ctx = new ConversationContext(new ContextKey(botId, userId));
                if (node.hasNonNull("latestContextToken")) {
                    ctx.setLatestContextToken(node.get("latestContextToken").asText());
                }
                if (node.hasNonNull("typingTicket")) {
                    ctx.setTypingTicket(node.get("typingTicket").asText());
                }
                contexts.put(userId, ctx);
            }
        } catch (Exception e) {
            log.warn("解析会话上下文失败，忽略该字段", e);
        }
        return contexts;
    }

    public record StoredSession(
        String sessionId,
        String botToken,
        String userId,
        String botId,
        String baseUrl,
        String updatesCursor,
        String contextsJson
    ) {
    }
}
