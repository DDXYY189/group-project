package com.example.group_demo.scheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class ReminderTimeParser {

    private static final DateTimeFormatter SPACE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter SPACE_SECONDS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private ReminderTimeParser() {
    }

    public static Long parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d+")) {
            return Long.parseLong(text);
        }
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试本地时间格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试空格格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, SPACE_FORMAT);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 继续尝试带秒格式
        }
        try {
            LocalDateTime dateTime = LocalDateTime.parse(text, SPACE_SECONDS_FORMAT);
            return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("触发时间格式不正确: " + value);
        }
    }
}
