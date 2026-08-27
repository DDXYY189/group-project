package com.example.group_demo.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderServiceTest {

    private ReminderService service;

    @BeforeEach
    void setUp() {
        String url = "jdbc:h2:mem:reminder-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ScheduledProperties properties = new ScheduledProperties();
        properties.setTimezone("Asia/Shanghai");
        service = new ReminderService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
    }

    @Test
    void addsAndFiresOneTimeReminder() {
        long future = System.currentTimeMillis() + 60_000;
        ReminderService.Reminder reminder =
            service.add("u1", "明天开会", "once", null, null, future);

        assertTrue(service.list("u1").stream().anyMatch(item -> item.id() == reminder.id()));
        assertTrue(service.due(future).stream().anyMatch(item -> item.id() == reminder.id()));
        assertFalse(service.due(future - 1000).stream().anyMatch(item -> item.id() == reminder.id()));

        service.afterFired(reminder.id(), true);
        assertTrue(service.list("u1").isEmpty());
    }

    @Test
    void dailyAndCronNextTimesAreCalculated() {
        ZoneId zone = ZoneId.of("Asia/Shanghai");
        long morning = ZonedDateTime.of(2026, 1, 1, 8, 0, 0, 0, zone)
            .toInstant().toEpochMilli();
        long today0830 = ZonedDateTime.of(2026, 1, 1, 8, 30, 0, 0, zone)
            .toInstant().toEpochMilli();
        long afterTime = ZonedDateTime.of(2026, 1, 1, 9, 0, 0, 0, zone)
            .toInstant().toEpochMilli();
        long tomorrow0830 = ZonedDateTime.of(2026, 1, 2, 8, 30, 0, 0, zone)
            .toInstant().toEpochMilli();

        assertEquals(today0830,
            ReminderService.nextTriggerAt("daily", "08:30", null, null, morning, zone));
        assertEquals(tomorrow0830,
            ReminderService.nextTriggerAt("daily", "08:30", null, null, afterTime, zone));
        assertEquals(tomorrow0830,
            ReminderService.nextTriggerAt("cron", null, "0 30 8 * * *", null, afterTime, zone));
    }

    @Test
    void rejectsInvalidReminders() {
        assertThrows(IllegalArgumentException.class,
            () -> service.add("u1", "开会", "once", null, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.add("u1", "开会", "daily", "25:99", null, null));
        assertThrows(IllegalArgumentException.class,
            () -> service.add("u1", "开会", "cron", null, "not-a-cron", null));
        assertThrows(IllegalArgumentException.class,
            () -> service.add("u1", "", "once", null, null, System.currentTimeMillis() + 60_000));
    }
}
