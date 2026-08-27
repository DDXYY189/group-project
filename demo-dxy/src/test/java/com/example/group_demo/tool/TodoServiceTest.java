package com.example.group_demo.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoServiceTest {

    private TodoService todoService;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:todo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        todoService = new TodoService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
    }

    @Test
    void addListAndDone() {
        assertEquals("已添加待办 #1：明天下午开会", todoService.add("u1", "明天下午开会"));
        assertTrue(todoService.list("u1").contains("明天下午开会"));
        assertEquals("已完成待办 #1", todoService.done("u1", 1));
        assertEquals("暂无待办事项", todoService.list("u1"));
    }

    @Test
    void usersAreIsolated() {
        todoService.add("u1", "写日报");
        todoService.add("u2", "写周报");

        assertTrue(todoService.list("u1").contains("写日报"));
        assertFalse(todoService.list("u1").contains("写周报"));
        assertTrue(todoService.list("u2").contains("写周报"));
    }

    @Test
    void rejectsBlankTodo() {
        assertThrows(IllegalArgumentException.class, () -> todoService.add("u1", "  "));
    }

    @Test
    void doneUnknownReturnsMessage() {
        assertEquals("未找到待办 #99", todoService.done("u1", 99));
    }

    @Test
    void clearAllRemovesEveryUsersTodos() {
        todoService.add("u1", "写日报");
        todoService.add("u2", "写周报");

        assertEquals(2, todoService.clearAll());
        assertEquals("暂无待办事项", todoService.list("u1"));
        assertEquals("暂无待办事项", todoService.list("u2"));
    }

    @Test
    void persistsAcrossServiceInstances() {
        String url = "jdbc:h2:mem:todo-persist-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        TodoService first =
            new TodoService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));
        first.add("u1", "写报告");

        TodoService second =
            new TodoService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")));

        assertTrue(second.list("u1").contains("写报告"));
    }
}
