package com.example.group_demo.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TodoServiceTest {

    private final TodoService todoService = new TodoService();

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
}
