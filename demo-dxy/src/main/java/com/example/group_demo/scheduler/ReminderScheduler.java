package com.example.group_demo.scheduler;

import com.example.group_demo.session.BotSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ScheduledProperties properties;
    private final ReminderService reminderService;
    private final BotSessionManager sessionManager;

    public ReminderScheduler(ScheduledProperties properties, ReminderService reminderService,
                             BotSessionManager sessionManager) {
        this.properties = properties;
        this.reminderService = reminderService;
        this.sessionManager = sessionManager;
    }

    @Scheduled(fixedDelayString = "${scheduler.reminder-poll-ms:15000}")
    public void scanDueReminders() {
        if (!properties.isEnabled()) {
            return;
        }
        List<ReminderService.Reminder> due = reminderService.due(System.currentTimeMillis());
        if (due.isEmpty()) {
            return;
        }
        for (ReminderService.Reminder reminder : due) {
            String text = "【定时提醒】" + reminder.content();
            int sent = sessionManager.sendToUser(reminder.userId(), text);
            if (sent == 0) {
                log.warn("定时提醒没有可投递的微信会话 id={} userId={}", reminder.id(), reminder.userId());
            }
            reminderService.afterFired(reminder.id(), sent > 0);
        }
    }
}
