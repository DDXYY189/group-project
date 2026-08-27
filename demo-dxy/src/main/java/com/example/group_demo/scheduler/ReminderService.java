package com.example.group_demo.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

@Service
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ZoneId zoneId;

    public ReminderService(JdbcTemplate jdbcTemplate, ScheduledProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.zoneId = ZoneId.of(properties.getTimezone());
        createSchema();
    }

    private void createSchema() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS reminder (
              id BIGINT AUTO_INCREMENT PRIMARY KEY,
              user_id VARCHAR(128) NOT NULL,
              content CLOB NOT NULL,
              schedule_type VARCHAR(16) NOT NULL,
              time_value VARCHAR(16),
              cron_expr VARCHAR(128),
              fire_at BIGINT,
              next_fire_at BIGINT,
              last_fired_at BIGINT,
              enabled BOOLEAN NOT NULL DEFAULT TRUE,
              created_at BIGINT NOT NULL
            )
            """);
    }

    public Reminder add(String userId, String content, String scheduleType,
                        String time, String cron, Long fireAt) {
        String type = normalizeType(scheduleType);
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            throw new IllegalArgumentException("提醒内容不能为空");
        }
        long now = System.currentTimeMillis();
        long next = nextTriggerAt(type, time, cron, fireAt, now, zoneId);
        if (next <= now) {
            throw new IllegalArgumentException("触发时间必须晚于当前时间");
        }
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO reminder
                  (user_id, content, schedule_type, time_value, cron_expr, fire_at, next_fire_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, new String[]{"id"});
            statement.setString(1, userId);
            statement.setString(2, text);
            statement.setString(3, type);
            statement.setString(4, time);
            statement.setString(5, cron);
            statement.setObject(6, fireAt);
            statement.setLong(7, next);
            statement.setLong(8, now);
            return statement;
        }, keyHolder);
        Number key = keyHolder.getKey();
        long id = key == null ? 0 : key.longValue();
        return new Reminder(id, userId, text, type, time, cron, fireAt, next, null, true, now);
    }

    public List<Reminder> list(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        return query("WHERE user_id = ? ORDER BY CASE WHEN next_fire_at IS NULL THEN 1 ELSE 0 END, next_fire_at",
            userId);
    }

    public List<Reminder> listAll() {
        return query("ORDER BY CASE WHEN next_fire_at IS NULL THEN 1 ELSE 0 END, next_fire_at", null);
    }

    public List<Reminder> due(long now) {
        return query("WHERE enabled = TRUE AND next_fire_at IS NOT NULL AND next_fire_at <= ? "
            + "ORDER BY next_fire_at", null, now);
    }

    public void afterFired(long id, boolean delivered) {
        Reminder reminder = find(id);
        if (reminder == null) {
            return;
        }
        if ("once".equals(reminder.scheduleType())) {
            jdbcTemplate.update("DELETE FROM reminder WHERE id = ?", id);
            return;
        }
        long now = System.currentTimeMillis();
        try {
            long next = nextTriggerAt(reminder.scheduleType(), reminder.timeValue(),
                reminder.cronExpr(), null, now, zoneId);
            jdbcTemplate.update(
                "UPDATE reminder SET next_fire_at = ?, last_fired_at = ? WHERE id = ?",
                next, now, id);
            log.info("定时提醒已触发 id={} delivered={} next={}", id, delivered, next);
        } catch (Exception e) {
            log.warn("定时提醒触发后无法计算下一次时间，停用提醒 id={}", id, e);
            jdbcTemplate.update("UPDATE reminder SET enabled = FALSE, last_fired_at = ? WHERE id = ?",
                now, id);
        }
    }

    public String remove(String userId, long id) {
        int deleted = jdbcTemplate.update(
            "DELETE FROM reminder WHERE id = ? AND user_id = ?", id, userId);
        return deleted > 0 ? "已删除定时提醒 #" + id : "未找到定时提醒 #" + id;
    }

    public Reminder find(long id) {
        List<Reminder> reminders = query("WHERE id = ?", null, id);
        return reminders.isEmpty() ? null : reminders.get(0);
    }

    public String describe(Reminder reminder) {
        String type = switch (reminder.scheduleType()) {
            case "daily" -> "每天 " + (reminder.timeValue() == null ? "--" : reminder.timeValue());
            case "cron" -> "Cron " + (reminder.cronExpr() == null ? "--" : reminder.cronExpr());
            default -> "一次性";
        };
        return "#" + reminder.id() + " " + reminder.content() + "（" + type
            + "，下次 " + formatTime(reminder.nextFireAt(), zoneId) + "）";
    }

    static long nextTriggerAt(String scheduleType, String time, String cron,
                              Long fireAt, long now, ZoneId zoneId) {
        return switch (normalizeType(scheduleType)) {
            case "daily" -> nextDaily(time, now, zoneId);
            case "cron" -> nextCron(cron, now, zoneId);
            default -> {
                if (fireAt == null) {
                    throw new IllegalArgumentException("一次性提醒缺少触发时间");
                }
                yield fireAt;
            }
        };
    }

    static String formatTime(Long epochMillis, ZoneId zoneId) {
        if (epochMillis == null) {
            return "待计算";
        }
        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zoneId)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private static long nextDaily(String time, long now, ZoneId zoneId) {
        LocalTime localTime;
        try {
            localTime = LocalTime.parse(time);
        } catch (Exception e) {
            throw new IllegalArgumentException("每天提醒的时间格式应为 HH:mm");
        }
        ZonedDateTime today = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId)
            .with(localTime);
        if (today.toInstant().toEpochMilli() <= now) {
            today = today.plusDays(1);
        }
        return today.toInstant().toEpochMilli();
    }

    private static long nextCron(String cron, long now, ZoneId zoneId) {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException("Cron 提醒缺少表达式");
        }
        CronExpression expression = CronExpression.parse(cron);
        ZonedDateTime next = expression.next(
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), zoneId));
        if (next == null) {
            throw new IllegalArgumentException("Cron 表达式没有未来触发时间: " + cron);
        }
        return next.toInstant().toEpochMilli();
    }

    private static String normalizeType(String scheduleType) {
        String type = scheduleType == null ? "once" : scheduleType.trim().toLowerCase();
        if (!List.of("once", "daily", "cron").contains(type)) {
            throw new IllegalArgumentException("不支持的定时类型: " + scheduleType);
        }
        return type;
    }

    private List<Reminder> query(String suffix, String userId, Object... extraArgs) {
        String sql = "SELECT id, user_id, content, schedule_type, time_value, cron_expr, "
            + "fire_at, next_fire_at, last_fired_at, enabled, created_at FROM reminder " + suffix;
        Object[] args;
        if (userId == null) {
            args = extraArgs;
        } else {
            args = new Object[extraArgs.length + 1];
            args[0] = userId;
            System.arraycopy(extraArgs, 0, args, 1, extraArgs.length);
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new Reminder(
            rs.getLong("id"),
            rs.getString("user_id"),
            rs.getString("content"),
            rs.getString("schedule_type"),
            rs.getString("time_value"),
            rs.getString("cron_expr"),
            (Long) rs.getObject("fire_at"),
            (Long) rs.getObject("next_fire_at"),
            (Long) rs.getObject("last_fired_at"),
            rs.getBoolean("enabled"),
            rs.getLong("created_at")
        ), args);
    }

    public record Reminder(
        long id,
        String userId,
        String content,
        String scheduleType,
        String timeValue,
        String cronExpr,
        Long fireAt,
        Long nextFireAt,
        Long lastFiredAt,
        boolean enabled,
        long createdAt
    ) {
    }
}
