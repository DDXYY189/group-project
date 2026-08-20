package com.example.group_demo.bot;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

/**
 * 按用户分片的消息分发器：同一用户的消息保持顺序，不同用户可并行处理。
 */
@Component
public class MessageDispatcher {

    private static final int WORKERS = 8;

    private final List<ExecutorService> workers = IntStream.range(0, WORKERS)
        .mapToObj(this::newWorker)
        .toList();

    private ExecutorService newWorker(int index) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ilink-message-handler-" + index);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void submit(String userId, Runnable task) {
        int slot = Math.floorMod(userId == null ? 0 : userId.hashCode(), WORKERS);
        workers.get(slot).execute(task);
    }

    @PreDestroy
    public void shutdown() {
        workers.forEach(ExecutorService::shutdownNow);
    }
}
