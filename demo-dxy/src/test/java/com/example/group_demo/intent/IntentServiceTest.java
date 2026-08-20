package com.example.group_demo.intent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class IntentServiceTest {

    private final IntentService intentService = new IntentService(null);

    @Test
    void classifiesImageRequestWithoutLlmCall() {
        Intent intent = intentService.classify("帮我画一只猫");

        assertEquals("image", intent.action());
        assertEquals("帮我画一只猫", intent.imagePrompt());
    }

    @Test
    void classifiesVoiceReplyRequest() {
        Intent intent = intentService.classify("用语音回复我今天的天气");

        assertEquals("voice", intent.action());
        assertNull(intent.imagePrompt());
    }

    @Test
    void defaultsToText() {
        Intent intent = intentService.classify("今天天气怎么样");

        assertEquals("text", intent.action());
        assertNull(intent.imagePrompt());
    }
}
