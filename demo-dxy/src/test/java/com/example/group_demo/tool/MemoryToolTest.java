package com.example.group_demo.tool;

import com.example.group_demo.llm.ConversationMemoryService;
import com.example.group_demo.llm.LlmProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryToolTest {

    @Test
    void clearsUserMemory() throws Exception {
        String url = "jdbc:h2:mem:memory-tool-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        LlmProperties properties = new LlmProperties();
        ConversationMemoryService memory =
            new ConversationMemoryService(new JdbcTemplate(dataSource), properties);
        memory.append("u1", "user", "我叫小明");

        MemoryTool tool = new MemoryTool(memory);
        String result = tool.execute("u1", new ObjectMapper().readTree("{}"));

        assertTrue(result.contains("清除"));
        assertTrue(memory.history("u1").isEmpty());
    }
}
