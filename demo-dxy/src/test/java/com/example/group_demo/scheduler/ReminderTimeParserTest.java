package com.example.group_demo.scheduler;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReminderTimeParserTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void parsesStandardFormat() {
        long expected = ZonedDateTime.of(2026, 8, 30, 19, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("2026-08-30 19:00"));
    }

    @Test
    void parsesTonightTime() {
        long now = ZonedDateTime.of(2026, 8, 30, 12, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 30, 19, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("晚上7点开会", now, ZONE));
    }

    @Test
    void rollsPastRelativeTimeToTomorrow() {
        long now = ZonedDateTime.of(2026, 8, 30, 20, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 31, 19, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("晚上7点", now, ZONE));
    }

    @Test
    void parsesTomorrowMorning() {
        long now = ZonedDateTime.of(2026, 8, 30, 20, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 31, 9, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("明天早上9点提醒我", now, ZONE));
    }

    @Test
    void parsesChineseNumerals() {
        long now = ZonedDateTime.of(2026, 8, 30, 10, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 30, 19, 30, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("今晚七点半", now, ZONE));
    }

    @Test
    void parsesNextWeekday() {
        long now = ZonedDateTime.of(2026, 8, 30, 10, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 31, 8, 30, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("下周一 8:30", now, ZONE));
    }

    @Test
    void parsesRelativeDuration() {
        long now = ZonedDateTime.of(2026, 8, 30, 10, 0, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        long expected = ZonedDateTime.of(2026, 8, 30, 10, 5, 0, 0, ZONE)
            .toInstant().toEpochMilli();
        assertEquals(expected, ReminderTimeParser.parse("5分钟后提醒我", now, ZONE));
    }

    @Test
    void rejectsUnrecognizedTime() {
        assertThrows(IllegalArgumentException.class,
            () -> ReminderTimeParser.parse("开会", System.currentTimeMillis(), ZONE));
    }
}
