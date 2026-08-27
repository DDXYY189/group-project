package com.example.group_demo.scheduler;

import com.example.group_demo.session.BotSessionManager;
import com.example.group_demo.news.NewsProperties;
import com.example.group_demo.news.NewsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ScheduledPushService {

    private static final Logger log = LoggerFactory.getLogger(ScheduledPushService.class);

    private final ScheduledProperties properties;
    private final NewsService newsService;
    private final NewsProperties newsProperties;
    private final BotSessionManager sessionManager;

    public ScheduledPushService(ScheduledProperties properties, NewsService newsService,
                                NewsProperties newsProperties, BotSessionManager sessionManager) {
        this.properties = properties;
        this.newsService = newsService;
        this.newsProperties = newsProperties;
        this.sessionManager = sessionManager;
    }

    @Scheduled(cron = "${scheduler.daily-news-cron:0 30 8 * * *}")
    public void pushDailyNews() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            String news = newsService.getHotNews(newsProperties.getMaxItems());
            String text = "早上好，今日热点：\n" + news;
            int sent = sessionManager.sendToAllKnownUsers(text);
            log.info("每日热点推送完成 sent={}", sent);
        } catch (Exception e) {
            log.warn("每日热点推送失败", e);
        }
    }
}
