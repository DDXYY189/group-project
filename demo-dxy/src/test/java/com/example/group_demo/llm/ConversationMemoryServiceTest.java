package com.example.group_demo.llm;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversationMemoryServiceTest {

    private ConversationMemoryService newMemory(int maxTurns) {
        LlmProperties properties = new LlmProperties();
        properties.getMemory().setMaxTurns(maxTurns);
        properties.getMemory().setSummaryEnabled(false);
        return newMemory(properties);
    }

    private ConversationMemoryService newMemory(LlmProperties properties) {
        String url = "jdbc:h2:mem:test-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        return new ConversationMemoryService(new JdbcTemplate(dataSource), properties);
    }

    @Test
    void keepsChronologicalHistory() {
        ConversationMemoryService memory = newMemory(5);
        memory.append("u1", "user", "a");
        memory.append("u1", "assistant", "b");
        memory.append("u1", "user", "c");
        memory.append("u1", "assistant", "d");

        List<ConversationMemoryService.ChatTurn> history = memory.history("u1");

        assertEquals(4, history.size());
        assertEquals("a", history.get(0).content());
        assertEquals("d", history.get(3).content());
    }

    @Test
    void trimsOldestTurnsBeyondWindow() {
        ConversationMemoryService memory = newMemory(2);
        for (int i = 0; i < 5; i++) {
            memory.append("u1", "user", "u" + i);
            memory.append("u1", "assistant", "a" + i);
        }

        List<ConversationMemoryService.ChatTurn> history = memory.history("u1");

        assertEquals(4, history.size());
        assertEquals("u3", history.get(0).content());
        assertEquals("a4", history.get(3).content());
    }

    @Test
    void keepsUsersSeparate() {
        ConversationMemoryService memory = newMemory(5);
        memory.append("u1", "user", "hello");
        memory.append("u2", "user", "world");

        assertTrue(memory.history("u1").stream().noneMatch(turn -> "world".equals(turn.content())));
        assertEquals(1, memory.history("u2").size());
    }

    @Test
    void clearsUserHistory() {
        ConversationMemoryService memory = newMemory(5);
        memory.append("u1", "user", "hello");

        memory.clear("u1");

        assertTrue(memory.history("u1").isEmpty());
    }

    @Test
    void persistsAcrossServiceInstances() {
        String url = "jdbc:h2:mem:persist-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        LlmProperties properties = new LlmProperties();
        properties.getMemory().setMaxTurns(5);

        ConversationMemoryService first = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        first.append("u1", "user", "我叫小明");
        first.append("u1", "assistant", "你好，小明");

        ConversationMemoryService second = new ConversationMemoryService(
            new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        List<ConversationMemoryService.ChatTurn> history = second.history("u1");

        assertEquals(2, history.size());
        assertEquals("我叫小明", history.get(0).content());
        assertEquals("你好，小明", history.get(1).content());
    }

    @Test
    void compactStoresSummaryAndRemovesOldTurns() {
        LlmProperties properties = new LlmProperties();
        properties.getMemory().setMaxTurns(5);
        ConversationMemoryService memory = newMemory(properties);
        for (int i = 0; i < 4; i++) {
            memory.append("u1", "user", "u" + i);
            memory.append("u1", "assistant", "a" + i);
        }

        memory.compact("u1", "摘要内容", 6);

        ConversationMemoryService.ChatContext context = memory.load("u1");
        assertEquals("摘要内容", context.summary());
        assertEquals(2, context.turns().size());
        assertEquals("u3", context.turns().get(0).content());
    }
}
