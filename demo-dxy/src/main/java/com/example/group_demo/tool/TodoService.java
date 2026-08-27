package com.example.group_demo.tool;

import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.util.List;

@Service
public class TodoService {

    private final JdbcTemplate jdbcTemplate;

    public TodoService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        createSchema();
    }

    private void createSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS todo_item (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id VARCHAR(128) NOT NULL,
              text CLOB NOT NULL,
              created_at BIGINT NOT NULL
            )
            """);
    }

    public String add(String userId, String text) {
        String content = text == null ? "" : text.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("待办内容不能为空");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO todo_item (user_id, text, created_at) VALUES (?, ?, ?)",
                new String[]{"id"}
            );
            statement.setString(1, userId);
            statement.setString(2, content);
            statement.setLong(3, System.currentTimeMillis());
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        int id = key == null ? 0 : key.intValue();
        return "已添加待办 #" + id + "：" + content;
    }

    public String list(String userId) {
        List<TodoItem> items = jdbcTemplate.query(
            "SELECT id, text FROM todo_item WHERE user_id = ? ORDER BY id ASC",
            (rs, rowNum) -> new TodoItem(rs.getInt(1), rs.getString(2)),
            userId
        );
        if (items.isEmpty()) {
            return "暂无待办事项";
        }
        List<String> lines = items.stream()
            .map(item -> "#" + item.id() + " " + item.text())
            .toList();
        return "当前待办：\n" + String.join("\n", lines);
    }

    public String done(String userId, int id) {
        if (id <= 0) {
            throw new IllegalArgumentException("待办编号无效: " + id);
        }
        int deleted = jdbcTemplate.update(
            "DELETE FROM todo_item WHERE id = ? AND user_id = ?",
            id, userId
        );
        return deleted > 0 ? "已完成待办 #" + id : "未找到待办 #" + id;
    }

    public int clearAll() {
        return jdbcTemplate.update("DELETE FROM todo_item");
    }

    public record TodoItem(int id, String text) {
    }
}
