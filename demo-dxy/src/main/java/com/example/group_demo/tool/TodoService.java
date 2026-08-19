package com.example.group_demo.tool;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TodoService {

    private final Map<String, Deque<TodoItem>> todosByUser = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    public String add(String userId, String text) {
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("待办内容不能为空");
        }
        Deque<TodoItem> items = todosByUser.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (items) {
            int id = nextId.getAndIncrement();
            items.addLast(new TodoItem(id, content));
            return "已添加待办 #" + id + "：" + content;
        }
    }

    public String list(String userId) {
        Deque<TodoItem> items = todosByUser.get(userId);
        if (items == null || items.isEmpty()) {
            return "暂无待办事项";
        }
        List<String> lines = new ArrayList<>();
        synchronized (items) {
            for (TodoItem item : items) {
                lines.add("#" + item.id() + " " + item.text());
            }
        }
        return "当前待办：\n" + String.join("\n", lines);
    }

    public String done(String userId, int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("待办编号无效: " + id);
        }
        Deque<TodoItem> items = todosByUser.get(userId);
        if (items == null) {
            return "未找到待办 #" + id;
        }
        synchronized (items) {
            boolean removed = items.removeIf(item -> item.id() == id);
            if (!removed) {
                return "未找到待办 #" + id;
            }
        }
        return "已完成待办 #" + id;
    }

    public record TodoItem(int id, String text) {
    }
}
