package com.example.group_demo.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordRagServiceTest {

    private final List<KnowledgeChunk> chunks = List.of(
        new KnowledgeChunk("campus-1", "校庆", "学校校庆日是每年 11 月 18 日，地点在大学生活动中心。"),
        new KnowledgeChunk("campus-2", "图书馆", "图书馆开放时间为工作日 8:00-22:00。"),
        new KnowledgeChunk("campus-3", "实验室", "人工智能实验室位于实验楼 B 座 302 室。")
    );

    private KeywordRagService newService(boolean enabled, int topK) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(enabled);
        properties.setTopK(topK);
        return new KeywordRagService(properties, chunks);
    }

    @Test
    void retrievesChunkByKeyword() {
        List<KnowledgeChunk> hits = newService(true, 3).retrieve("校庆是什么时候");
        assertFalse(hits.isEmpty());
        assertEquals("campus-1", hits.get(0).id());
        assertTrue(hits.get(0).content().contains("11 月 18 日"));
    }

    @Test
    void limitsResultCountByTopK() {
        List<KnowledgeChunk> hits = newService(true, 1).retrieve("图书馆 实验室 校庆");
        assertEquals(1, hits.size());
    }

    @Test
    void returnsEmptyWhenDisabled() {
        assertTrue(newService(false, 3).retrieve("校庆是什么时候").isEmpty());
    }

    @Test
    void returnsEmptyWhenNoKeywordMatches() {
        assertTrue(newService(true, 3).retrieve("今天吃什么").isEmpty());
    }

    @Test
    void buildsPromptWithRetrievedKnowledge() {
        String prompt = newService(true, 3).buildEnhancedPrompt(chunks.subList(0, 1));
        assertTrue(prompt.contains("参考资料"));
        assertTrue(prompt.contains("11 月 18 日"));
    }
}
