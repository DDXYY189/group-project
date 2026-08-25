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

/**
 * RAG 开启/关闭对比测试 + Skill 执行测试
 *
 * 验证完整消息路由三级流程：
 * 1. Skill 关键词命中 → 直接执行返回
 * 2. RAG 开启 + 关键词命中 → 检索到知识库上下文
 * 3. RAG 关闭 + 关键词命中 → 无上下文返回
 * 4. 都未命中 → 返回 null（由 LLM 兜底）
 *
 * 使用纯单元测试（不启动 Spring 上下文），避免触发 CommandLineRunner 中的微信登录流程。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RagComparisonTest {

    private static RagService ragService;
    private static SkillService skillService;

    @BeforeAll
    static void setUp() throws Exception {
        ragService = new RagService();
        // 手动调用 @PostConstruct 方法初始化知识库
        Method init = RagService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(ragService);
        // 手动开启 RAG（模拟 @Value("${rag.enabled:true}") 的默认值）
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

        System.out.println("\n========== 测试1: RAG开启 + 命中关键词 ==========");
        System.out.println("查询: " + query);
        System.out.println("RAG状态: " + (ragService.isEnabled() ? "开启" : "关闭"));
        System.out.println("检索结果:\n" + context);
        System.out.println("================================================\n");

        assertNotNull(context, "RAG开启时应检索到上下文");
        assertTrue(context.contains("Function Calling"), "上下文应包含Function Calling相关知识");
        assertTrue(context.contains("知识库参考信息"), "上下文应包含知识库标记");
    }

    @Test
    @Order(2)
    @DisplayName("测试2: RAG关闭 + 命中关键词 → 应返回null（无增强）")
    void testRagDisabledWithMatch() {
        ragService.setEnabled(false);

        String query = "什么是Function Calling？";
        String context = ragService.retrieve(query);

        System.out.println("\n========== 测试2: RAG关闭 + 命中关键词 ==========");
        System.out.println("查询: " + query);
        System.out.println("RAG状态: " + (ragService.isEnabled() ? "开启" : "关闭"));
        System.out.println("检索结果: " + context);
        System.out.println("================================================\n");

        assertNull(context, "RAG关闭时不应检索到上下文");

        // 恢复默认状态
        ragService.setEnabled(true);
    }

    @Test
    @Order(3)
    @DisplayName("测试3: RAG开启 + 未命中关键词 → 应返回null")
    void testRagEnabledNoMatch() {
        ragService.setEnabled(true);

        String query = "今天中午吃什么好呢？";
        String context = ragService.retrieve(query);

        System.out.println("\n========== 测试3: RAG开启 + 未命中关键词 ==========");
        System.out.println("查询: " + query);
        System.out.println("RAG状态: " + (ragService.isEnabled() ? "开启" : "关闭"));
        System.out.println("检索结果: " + context);
        System.out.println("================================================\n");

        assertNull(context, "未命中关键词时应返回null");
    }

    @Test
    @Order(4)
    @DisplayName("测试4: RAG开启/关闭对比 - 同一查询结果不同")
    void testRagComparison() {
        String[] testQueries = {
            "Spring Boot是什么框架？",
            "什么是RAG检索增强生成？",
            "微信机器人怎么开发的？",
            "通义千问模型有什么用？"
        };

        System.out.println("\n========== 测试4: RAG开启/关闭对比测试 ==========");
        System.out.println("知识库文档数: " + ragService.getDocumentCount());

        for (String q : testQueries) {
            ragService.setEnabled(true);
            String withRag = ragService.retrieve(q);

            ragService.setEnabled(false);
            String withoutRag = ragService.retrieve(q);

            System.out.println("\n查询: " + q);
            System.out.println("  RAG开启: " + (withRag != null ? "✅ 检索到上下文(" + withRag.length() + "字符)" : "❌ 无匹配"));
            System.out.println("  RAG关闭: " + (withoutRag != null ? "✅ 检索到上下文" : "❌ 无匹配"));
        }

        ragService.setEnabled(true);
        System.out.println("================================================\n");
    }

    @Test
    @Order(5)
    @DisplayName("测试5: Skill关键词命中 → 应直接执行返回运势结果")
    void testSkillMatch() {
        String[] skillQueries = {"运势", "今日运势", "白羊座运势", "抽签", "占卜一下"};

        System.out.println("\n========== 测试5: Skill关键词命中测试 ==========");

        for (String query : skillQueries) {
            String result = skillService.tryMatch(query);
            System.out.println("\n查询: " + query);
            System.out.println("Skill匹配: " + (result != null ? "✅ 命中" : "❌ 未命中"));
            if (result != null) {
                System.out.println("执行结果:\n" + result);
            }
        }
        System.out.println("================================================\n");

        String result = skillService.tryMatch("今日运势");
        assertNotNull(result, "运势查询应命中Skill");
        assertTrue(result.contains("今日运势"), "结果应包含运势信息");
        assertTrue(result.contains("幸运数字"), "结果应包含幸运数字");
    }

    @Test
    @Order(6)
    @DisplayName("测试6: Skill未命中关键词 → 应返回null")
    void testSkillNoMatch() {
        String[] noMatchQueries = {"今天天气怎么样", "现在几点了", "你好", "画一只猫", "帮我写代码"};

        System.out.println("\n========== 测试6: Skill未命中关键词测试 ==========");

        for (String query : noMatchQueries) {
            String result = skillService.tryMatch(query);
            System.out.println("查询: " + query + " → " + (result != null ? "命中(异常!)" : "未命中 ✅"));
            assertNull(result, "非运势查询不应命中Skill: " + query);
        }
        System.out.println("================================================\n");
    }

    @Test
    @Order(7)
    @DisplayName("测试7: 完整路由流程模拟 - Skill→RAG→LLM三级路由")
    void testFullRoutingFlow() {
        String[][] testCases = {
            // {消息, 期望路由路径}
            {"今日运势", "Skill"},              // 命中运势关键词
            {"什么是RAG？", "RAG"},              // 命中RAG关键词
            {"Spring Boot是什么", "RAG"},       // 命中Spring Boot关键词
            {"今天心情不错啊", "LLM"},           // 未命中任何关键词
            {"你好呀", "LLM"},                  // 未命中任何关键词
            {"帮我占卜一下", "Skill"},           // 命中占卜关键词
            {"Function Calling是什么", "RAG"},  // 命中Function Calling关键词
        };

        ragService.setEnabled(true);

        System.out.println("\n========== 测试7: 完整路由流程模拟 ==========");
        System.out.println("路由流程: Skill → RAG → LLM 兜底\n");

        int passCount = 0;
        for (String[] tc : testCases) {
            String message = tc[0];
            String expectedRoute = tc[1];
            String actualRoute;

            // 模拟路由逻辑
            String skillResult = skillService.tryMatch(message);
            if (skillResult != null) {
                actualRoute = "Skill";
            } else {
                String ragContext = ragService.retrieve(message);
                if (ragContext != null) {
                    actualRoute = "RAG";
                } else {
                    actualRoute = "LLM";
                }
            }

            String status = actualRoute.equals(expectedRoute) ? "✅" : "❌";
            if (actualRoute.equals(expectedRoute)) passCount++;
            System.out.println(status + " 消息: \"" + message + "\"");
            System.out.println("   期望路由: " + expectedRoute + " | 实际路由: " + actualRoute);
            System.out.println();
        }
        System.out.println("通过率: " + passCount + "/" + testCases.length);
        System.out.println("================================================\n");

        assertEquals(testCases.length, passCount, "所有路由测试应通过");
    }
}
