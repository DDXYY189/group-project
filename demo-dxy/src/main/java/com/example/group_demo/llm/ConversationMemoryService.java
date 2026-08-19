package com.example.group_demo.llm;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ConversationMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final int maxTurns;
    private final long ttlMillis;
    private final int hardCapMessages;

    public ConversationMemoryService(JdbcTemplate jdbcTemplate, LlmProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        LlmProperties.Memory memory = properties.getMemory();
        this.maxTurns = Math.max(1, memory.getMaxTurns());
        this.ttlMillis = Math.max(1, memory.getTtlMinutes()) * 60_000L;
        int summaryThresholdTurns = Math.max(1, memory.getSummaryThresholdTurns());
        int hardCapTurns;
        if (memory.isSummaryEnabled()) {
            hardCapTurns = Math.max(this.maxTurns, summaryThresholdTurns + 1);
        } else {
            hardCapTurns = this.maxTurns;
        }
        this.hardCapMessages = hardCapTurns * 2;
        createSchema();
    }

    private void createSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_message (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id VARCHAR(128) NOT NULL,
              role VARCHAR(16) NOT NULL,
              content CLOB NOT NULL,
              created_at BIGINT NOT NULL
            )
            """);
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS conversation_summary (
              user_id VARCHAR(128) PRIMARY KEY,
              summary CLOB NOT NULL,
              updated_at BIGINT NOT NULL
            )
            """);
    }

    public ChatContext load(String userId) {
        if (userId == null || userId.isBlank()) {
            return new ChatContext(null, List.of());
        }
        long cutoff = System.currentTimeMillis() - ttlMillis;
        jdbcTemplate.update(
            "DELETE FROM conversation_message WHERE user_id = ? AND created_at < ?",
            userId, cutoff
        );

        String summary = jdbcTemplate.query(
            "SELECT summary FROM conversation_summary WHERE user_id = ?",
            rs -> rs.next() ? rs.getString(1) : null,
            userId
        );

        List<ChatTurn> turns = jdbcTemplate.query(
            """
            SELECT role, content, created_at
            FROM conversation_message
            WHERE user_id = ?
            ORDER BY id DESC
            LIMIT ?
            """,
            (rs, rowNum) -> new ChatTurn(rs.getString(1), rs.getString(2), rs.getLong(3)),
            userId,
            hardCapMessages
        );
        Collections.reverse(turns);
        return new ChatContext(summary, List.copyOf(turns));
    }

    public List<ChatTurn> history(String userId) {
        return load(userId).turns();
    }

    public void append(String userId, String role, String content) {
        if (userId == null || userId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        jdbcTemplate.update(
            "INSERT INTO conversation_message (user_id, role, content, created_at) VALUES (?, ?, ?, ?)",
            userId, role, content, System.currentTimeMillis()
        );
        trim(userId);
    }

    public void compact(String userId, String summary, int coveredMessageCount) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        if (summary != null && !summary.isBlank()) {
            jdbcTemplate.update(
                "MERGE INTO conversation_summary (user_id, summary, updated_at) KEY(user_id) VALUES (?, ?, ?)",
                userId, summary, System.currentTimeMillis()
            );
        }
        deleteOldest(userId, coveredMessageCount);
    }

    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        jdbcTemplate.update("DELETE FROM conversation_message WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM conversation_summary WHERE user_id = ?", userId);
    }

    private void trim(String userId) {
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM conversation_message WHERE user_id = ?",
            Long.class,
            userId
        );
        if (count == null || count <= hardCapMessages) {
            return;
        }
        deleteOldest(userId, (int) (count - hardCapMessages));
    }

    private void deleteOldest(String userId, int count) {
        if (count <= 0) {
            return;
        }
        List<Long> ids = jdbcTemplate.queryForList(
            "SELECT id FROM conversation_message WHERE user_id = ? ORDER BY id LIMIT ?",
            Long.class,
            userId,
            count
        );
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        jdbcTemplate.update(
            "DELETE FROM conversation_message WHERE id IN (" + placeholders + ")",
            ids.toArray()
        );
    }

    public record ChatTurn(String role, String content, long createdAt) {
    }

    public record ChatContext(String summary, List<ChatTurn> turns) {
    }
}
