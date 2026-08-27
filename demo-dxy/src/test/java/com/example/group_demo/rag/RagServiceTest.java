package com.example.group_demo.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagServiceTest {

    private KnowledgeBase knowledgeBase;
    private RagProperties enabledProps;
    private RagProperties disabledProps;

    @BeforeEach
    void setUp() {
        knowledgeBase = new KnowledgeBase();
        knowledgeBase.load();

        enabledProps = new RagProperties();
        enabledProps.setEnabled(true);
        enabledProps.setKeywords(List.of("无锡", "旅游", "景点", "美食"));

        disabledProps = new RagProperties();
        disabledProps.setEnabled(false);
    }

    @Test
    void knowledgeBaseLoadsDocuments() {
        assertTrue(knowledgeBase.size() >= 6,
            "知识库应至少加载 6 篇无锡旅游文档，实际: " + knowledgeBase.size());
    }

    @Test
    void enabledRagRetrievesAndAugments() {
        RagService ragService = new RagService(enabledProps, knowledgeBase);
        assertTrue(ragService.shouldRetrieve("无锡有什么景点"));
        String augmented = ragService.augmentPrompt("你是助手", "无锡景点推荐");
        assertNotNull(augmented);
        assertTrue(augmented.contains("知识库"));
        assertTrue(augmented.contains("鼋头渚") || augmented.contains("灵山"));
    }

    @Test
    void disabledRagDoesNotRetrieve() {
        RagService ragService = new RagService(disabledProps, knowledgeBase);
        assertFalse(ragService.shouldRetrieve("无锡有什么景点"));
        assertEquals("你是助手", ragService.augmentPrompt("你是助手", "无锡景点"));
    }

    @Test
    void noKeywordMatchReturnsBasePrompt() {
        RagService ragService = new RagService(enabledProps, knowledgeBase);
        String result = ragService.augmentPrompt("你是助手", "今天心情真好");
        assertEquals("你是助手", result);
    }

    @Test
    void ragComparisonTest() {
        RagService enabledRag = new RagService(enabledProps, knowledgeBase);
        RagService disabledRag = new RagService(disabledProps, knowledgeBase);

        String query = "无锡有什么好吃的";
        boolean enabledRetrieves = enabledRag.shouldRetrieve(query);
        boolean disabledRetrieves = disabledRag.shouldRetrieve(query);

        assertTrue(enabledRetrieves, "RAG 开启时应触发检索");
        assertFalse(disabledRetrieves, "RAG 关闭时不应触发检索");

        String enabledPrompt = enabledRag.augmentPrompt("你是助手", query);
        String disabledPrompt = disabledRag.augmentPrompt("你是助手", query);

        assertNotEquals(enabledPrompt, disabledPrompt,
            "开启和关闭 RAG 的 Prompt 应不同");
        assertTrue(enabledPrompt.contains("酱排骨") || enabledPrompt.contains("小笼包"),
            "开启 RAG 时 Prompt 应包含无锡美食知识");
        assertFalse(disabledPrompt.contains("酱排骨"),
            "关闭 RAG 时 Prompt 不应包含无锡美食知识");
    }
}
