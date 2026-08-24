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
        enabledProps.setKeywords(List.of("项目", "功能", "工具"));

        disabledProps = new RagProperties();
        disabledProps.setEnabled(false);
    }

    @Test
    void knowledgeBaseLoadsDocuments() {
        assertTrue(knowledgeBase.size() >= 3,
            "知识库应至少加载 3 篇文档，实际: " + knowledgeBase.size());
    }

    @Test
    void enabledRagRetrievesAndAugments() {
        RagService ragService = new RagService(enabledProps, knowledgeBase);
        assertTrue(ragService.shouldRetrieve("这个项目用了什么架构"));
        String augmented = ragService.augmentPrompt("你是助手", "项目架构是什么");
        assertNotNull(augmented);
        assertTrue(augmented.contains("知识库"));
        assertTrue(augmented.contains("Spring Boot"));
    }

    @Test
    void disabledRagDoesNotRetrieve() {
        RagService ragService = new RagService(disabledProps, knowledgeBase);
        assertFalse(ragService.shouldRetrieve("项目架构是什么"));
    }

    @Test
    void noKeywordMatchReturnsBasePrompt() {
        RagService ragService = new RagService(enabledProps, knowledgeBase);
        String result = ragService.augmentPrompt("你是助手", "你好呀");
        assertEquals("你是助手", result);
    }

    @Test
    void comparisonTest() {
        RagService enabledRag = new RagService(enabledProps, knowledgeBase);
        RagService disabledRag = new RagService(disabledProps, knowledgeBase);

        String query = "项目用了什么技术栈";
        boolean enabledRetrieves = enabledRag.shouldRetrieve(query);
        boolean disabledRetrieves = disabledRag.shouldRetrieve(query);

        assertTrue(enabledRetrieves, "RAG 开启时应触发检索");
        assertFalse(disabledRetrieves, "RAG 关闭时不应触发检索");

        String enabledPrompt = enabledRag.augmentPrompt("你是助手", query);
        String disabledPrompt = disabledRag.augmentPrompt("你是助手", query);

        assertNotEquals(enabledPrompt, disabledPrompt,
            "开启和关闭 RAG 的 Prompt 应不同");
        assertTrue(enabledPrompt.contains("Spring Boot"),
            "开启 RAG 时 Prompt 应包含知识库内容");
        assertFalse(disabledPrompt.contains("Spring Boot"),
            "关闭 RAG 时 Prompt 不应包含知识库内容");
    }
}
