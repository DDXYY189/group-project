package com.example.group_demo.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageDispatcherTest {

    private MessageDispatcher dispatcher;

    @AfterEach
    void stopDispatcher() {
        if (dispatcher != null) {
            dispatcher.shutdown();
        }
    }

    @Test
    void preservesOrderForSameUser() throws Exception {
        dispatcher = new MessageDispatcher();
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(2);

        dispatcher.submit("u1", () -> {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            order.add(1);
            done.countDown();
        });
        dispatcher.submit("u1", () -> {
            order.add(2);
            done.countDown();
        });

        assertTrue(done.await(5, TimeUnit.SECONDS));
        assertEquals(List.of(1, 2), order);
    }
}
