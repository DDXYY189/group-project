package com.example.demo_wkx;

import com.example.demo_wkx.rag.RagService;
import com.example.demo_wkx.skill.SkillService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagComparisonTest {

    private static RagService ragService;
    private static SkillService skillService;

    @BeforeAll
    static void setUp() throws Exception {
        ragService = new RagService();
        Method init = RagService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(ragService);
        ragService.setEnabled(true);
        skillService = new SkillService();
    }

    @Test
    @Order(1)
    @DisplayName("测试1: RAG开启 + 命中关键词 → 应检索到知识库上下文")
    void testRagEnabledWithMatch() {
        ragService.setEnabled(true);
        String query = "什么是Function Calling？";
        String context = ragService.retrieve(query);
        assertNotNull(context, "RAG开启时应检索到上下文");
        assertTrue(context.contains("Function Calling"));
        assertTrue(context.contains("知识库参考信息"));
    }

    @Test
    @Order(2)
    @DisplayName("测试2: RAG关闭 + 命中关键词 → 应返回null")
    void testRagDisabledWithMatch() {
        ragService.setEnabled(false);
        String context = ragService.retrieve("什么是Function Calling？");
        assertNull(context, "RAG关闭时不应检索到上下文");
        ragService.setEnabled(true);
    }

    @Test
    @Order(3)
    @DisplayName("测试3: RAG开启 + 未命中关键词 → 应返回null")
    void testRagEnabledNoMatch() {
        ragService.setEnabled(true);
        String context = ragService.retrieve("今天中午吃什么好呢？");
        assertNull(context, "未命中关键词时应返回null");
    }

    @Test
    @Order(4)
    @DisplayName("测试4: RAG开启/关闭对比")
    void testRagComparison() {
        String[] testQueries = {"Spring Boot是什么框架？", "什么是RAG检索增强生成？", "微信机器人怎么开发的？", "通义千问模型有什么用？"};
        for (String q : testQueries) {
            ragService.setEnabled(true);
            String withRag = ragService.retrieve(q);
            ragService.setEnabled(false);
            String withoutRag = ragService.retrieve(q);
            assertNotNull(withRag, "RAG开启时应检索到: " + q);
            assertNull(withoutRag, "RAG关闭时不应检索到: " + q);
        }
        ragService.setEnabled(true);
    }

    @Test
    @Order(5)
    @DisplayName("测试5: Skill关键词命中 → 应直接执行返回运势结果")
    void testSkillMatch() {
        String result = skillService.tryMatch("今日运势");
        assertNotNull(result, "运势查询应命中Skill");
        assertTrue(result.contains("今日运势"));
        assertTrue(result.contains("幸运数字"));
    }

    @Test
    @Order(6)
    @DisplayName("测试6: Skill未命中关键词 → 应返回null")
    void testSkillNoMatch() {
        String[] noMatchQueries = {"今天天气怎么样", "现在几点了", "你好", "画一只猫", "帮我写代码"};
        for (String query : noMatchQueries) {
            assertNull(skillService.tryMatch(query), "非运势查询不应命中Skill: " + query);
        }
    }

    @Test
    @Order(7)
    @DisplayName("测试7: 完整路由流程模拟 - Skill→RAG→LLM三级路由")
    void testFullRoutingFlow() {
        String[][] testCases = {
            {"今日运势", "Skill"},
            {"什么是RAG？", "RAG"},
            {"Spring Boot是什么", "RAG"},
            {"今天心情不错啊", "LLM"},
            {"你好呀", "LLM"},
            {"帮我占卜一下", "Skill"},
            {"Function Calling是什么", "RAG"},
        };
        ragService.setEnabled(true);
        int passCount = 0;
        for (String[] tc : testCases) {
            String actualRoute;
            String skillResult = skillService.tryMatch(tc[0]);
            if (skillResult != null) {
                actualRoute = "Skill";
            } else {
                String ragContext = ragService.retrieve(tc[0]);
                actualRoute = ragContext != null ? "RAG" : "LLM";
            }
            if (actualRoute.equals(tc[1])) passCount++;
        }
        assertEquals(testCases.length, passCount, "所有路由测试应通过");
    }
}
