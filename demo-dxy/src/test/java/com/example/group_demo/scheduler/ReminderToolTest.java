package com.example.group_demo.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ReminderToolTest {

    @Test
    void addListAndDeleteViaTool() throws Exception {
        String url = "jdbc:h2:mem:reminder-tool-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ScheduledProperties properties = new ScheduledProperties();
        ReminderService service =
            new ReminderService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        ReminderTool tool = new ReminderTool(service);
        ObjectMapper mapper = new ObjectMapper();

        String added = tool.execute("u1", mapper.readTree("""
            {"action":"add","content":"写日报","schedule_type":"daily","time":"18:00"}
            """));
        assertTrue(added.contains("已创建定时提醒"));

        String listed = tool.execute("u1", mapper.readTree("{\"action\":\"list\"}"));
        assertTrue(listed.contains("写日报"));

        String deleted = tool.execute("u1", mapper.readTree("{\"action\":\"delete\",\"id\":1}"));
        assertTrue(deleted.contains("已删除"));
    }

    @Test
    void addsOneTimeReminderWithNaturalTimeText() throws Exception {
        String url = "jdbc:h2:mem:reminder-tool-natural-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ScheduledProperties properties = new ScheduledProperties();
        ReminderService service =
            new ReminderService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        ReminderTool tool = new ReminderTool(service);
        ObjectMapper mapper = new ObjectMapper();

        String added = tool.execute("u1", mapper.readTree("""
            {"action":"add","content":"晚上开会","schedule_type":"once","time_text":"晚上7点"}
            """));
        assertTrue(added.contains("已创建定时提醒"));

        List<ReminderService.Reminder> reminders = service.list("u1");
        assertTrue(reminders.stream().anyMatch(reminder ->
            reminder.content().equals("晚上开会") && reminder.fireAt() != null));
    }

    @Test
    void addsDailyReminderWithNaturalTimeText() throws Exception {
        String url = "jdbc:h2:mem:reminder-tool-daily-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        ScheduledProperties properties = new ScheduledProperties();
        ReminderService service =
            new ReminderService(new JdbcTemplate(new DriverManagerDataSource(url, "sa", "")), properties);
        ReminderTool tool = new ReminderTool(service);
        ObjectMapper mapper = new ObjectMapper();

        String added = tool.execute("u1", mapper.readTree("""
            {"action":"add","content":"每天开会","schedule_type":"daily","time_text":"晚上7点"}
            """));
        assertTrue(added.contains("每天 19:00"));
    }
}
