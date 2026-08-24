package com.example.group_demo.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 极简关键词检索版 RAG 模块对比测试。
 *
 * 测试目标：
 *   ① RAG 开启 + 关键词命中 → 返回知识片段（用于增强 Prompt）
 *   ② RAG 关闭 → 跳过全部 RAG 逻辑，返回空列表
 *   ③ 不同关键词命中不同知识条目
 *   ④ 无匹配关键词 → 返回空列表
 *   ⑤ buildAugmentedPrompt 正确拼接增强文本
 *   ⑥ maxResults 限制返回条目数
 */
class RagServiceTest {

    private RagProperties properties;
    private RagService ragService;

    /**
     * 构建一个启用 RAG 的测试实例，手动加载知识库。
     * 知识库内容在 init() 中通过 ClassPathResource 加载，
     * 这里手动调用 loadKnowledgeBase 等效逻辑来初始化。
     */
    @BeforeEach
    void setUp() {
        properties = new RagProperties();
        properties.setEnableRag(true);
        properties.setKnowledgeBasePath("rag/knowledge-base.txt");
        properties.setMaxResults(3);
        ragService = new RagService(properties);
        // 手动触发初始化（@PostConstruct 在单元测试中不会自动执行）
        ragService.init();
    }

    @Test
    @DisplayName("① RAG开启 + 关键词命中 → 返回知识片段")
    void searchEnabledAndMatched() {
        // "你们公司介绍一下" 包含知识库关键词 "公司介绍"
        List<String> results = ragService.search("你们公司介绍一下");
        assertFalse(results.isEmpty(), "RAG 开启时命中关键词应返回知识片段");
        assertTrue(results.get(0).contains("人工智能"), "应返回公司介绍内容");
        System.out.println("【RAG开启-命中】检索结果:\n" + results.get(0));
    }

    @Test
    @DisplayName("② RAG关闭 → 跳过全部逻辑，返回空列表")
    void searchDisabled() {
        // 关闭 RAG 开关
        properties.setEnableRag(false);
        RagService disabledRag = new RagService(properties);
        disabledRag.init();

        // 同样的输入，RAG 关闭时应返回空列表
        List<String> results = disabledRag.search("你们公司介绍一下");
        assertTrue(results.isEmpty(), "RAG 关闭时应跳过检索，返回空列表");
        assertFalse(disabledRag.isEnabled(), "isEnabled() 应返回 false");
        System.out.println("【RAG关闭】检索结果为空，跳过了全部 RAG 逻辑");
    }

    @Test
    @DisplayName("③ 不同关键词命中不同知识条目")
    void searchDifferentKeywords() {
        // "价格多少" → 命中价格条目
        List<String> priceResults = ragService.search("你们价格多少");
        assertFalse(priceResults.isEmpty(), "应命中价格条目");
        assertTrue(priceResults.get(0).contains("299"), "应返回价格信息");

        // "怎么联系" → 命中联系方式条目
        List<String> contactResults = ragService.search("怎么联系你们");
        assertFalse(contactResults.isEmpty(), "应命中联系方式条目");
        assertTrue(contactResults.get(0).contains("400-888-8888"), "应返回联系方式信息");

        System.out.println("【价格命中】:\n" + priceResults.get(0));
        System.out.println("【联系方式命中】:\n" + contactResults.get(0));
    }

    @Test
    @DisplayName("④ 无匹配关键词 → 返回空列表")
    void searchNoMatch() {
        List<String> results = ragService.search("今天天气真好啊");
        assertTrue(results.isEmpty(), "无匹配关键词应返回空列表");
        System.out.println("【无命中】检索结果为空，将进入 LLM 兜底闲聊");
    }

    @Test
    @DisplayName("⑤ buildAugmentedPrompt 正确拼接增强文本")
    void buildAugmentedPrompt() {
        List<String> fragments = List.of("知识片段A", "知识片段B");
        String prompt = ragService.buildAugmentedPrompt(fragments);
        assertTrue(prompt.contains("参考信息"), "应包含参考信息引导语");
        assertTrue(prompt.contains("【参考1】"), "应包含参考1标记");
        assertTrue(prompt.contains("知识片段A"), "应包含第一段知识");
        assertTrue(prompt.contains("【参考2】"), "应包含参考2标记");
        assertTrue(prompt.contains("知识片段B"), "应包含第二段知识");
        System.out.println("【增强Prompt】:\n" + prompt);
    }

    @Test
    @DisplayName("⑥ maxResults 限制返回条目数")
    void searchMaxResultsLimit() {
        // "产品 价格 怎么联系" 能命中多条，但 maxResults=3
        List<String> results = ragService.search("产品价格怎么联系");
        assertTrue(results.size() <= 3, "返回条目数不应超过 maxResults=3");
        System.out.println("【多关键词命中】返回 " + results.size() + " 条知识（上限3条）");
    }

    @Test
    @DisplayName("对比测试：同一输入在RAG开启/关闭下的差异")
    void comparisonTest() {
        String userInput = "你们公司介绍一下";

        // RAG 开启
        List<String> enabledResults = ragService.search(userInput);
        System.out.println("=== 对比测试 ===");
        System.out.println("用户输入: " + userInput);
        System.out.println("RAG开启 → 命中 " + enabledResults.size() + " 条知识");
        System.out.println("知识内容: " + (enabledResults.isEmpty() ? "（空）" : enabledResults.get(0)));
        assertFalse(enabledResults.isEmpty(), "RAG 开启时应命中知识");

        // RAG 关闭
        properties.setEnableRag(false);
        RagService disabledRag = new RagService(properties);
        disabledRag.init();
        List<String> disabledResults = disabledRag.search(userInput);
        System.out.println("RAG关闭 → 命中 " + disabledResults.size() + " 条知识");
        System.out.println("知识内容: " + (disabledResults.isEmpty() ? "（空，跳过RAG）" : disabledResults.get(0)));
        assertTrue(disabledResults.isEmpty(), "RAG 关闭时应跳过全部检索");

        System.out.println("结论: 开启RAG时知识库内容会被检索并增强Prompt；关闭RAG时直接跳过，走LLM兜底闲聊");
    }
}
