package com.example.group_demo.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "scheduler")
public class ScheduledProperties {

    private boolean enabled = true;
    private String dailyNewsCron = "0 30 8 * * *";
    private long reminderPollMs = 15000;
    private String timezone = "Asia/Shanghai";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDailyNewsCron() {
        return dailyNewsCron;
    }

    public void setDailyNewsCron(String dailyNewsCron) {
        this.dailyNewsCron = dailyNewsCron;
    }

    public long getReminderPollMs() {
        return reminderPollMs;
    }

    public void setReminderPollMs(long reminderPollMs) {
        this.reminderPollMs = reminderPollMs;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }
}
