package com.example.group_demo.intent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ImageTextMerger {

    private final long windowMs;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public ImageTextMerger(@Value("${bot.merge-window-ms:8000}") long windowMs) {
        this.windowMs = windowMs;
    }

    public Optional<Pending> tryMergeImage(String userId, byte[] imageBytes, String fileName) {
        Pending existing = pending.get(userId);
        long now = System.currentTimeMillis();
        if (existing != null && existing.text() != null && !existing.expired(now, windowMs)) {
            pending.remove(userId);
            return Optional.of(new Pending(existing.text(), imageBytes, fileName, now));
        }
        pending.put(userId, new Pending(null, imageBytes, fileName, now));
        return Optional.empty();
    }

    public Optional<Pending> tryMergeText(String userId, String text) {
        Pending existing = pending.get(userId);
        long now = System.currentTimeMillis();
        if (existing != null && existing.imageBytes() != null && !existing.expired(now, windowMs)) {
            pending.remove(userId);
            return Optional.of(new Pending(text, existing.imageBytes(), existing.fileName(), now));
        }
        pending.put(userId, new Pending(text, null, null, now));
        return Optional.empty();
    }

    public void clear(String userId) {
        pending.remove(userId);
    }

    public record Pending(String text, byte[] imageBytes, String fileName, long createdAt) {
        public boolean expired(long now, long windowMs) {
            return now - createdAt > windowMs;
        }
    }
}
