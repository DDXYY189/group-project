package com.example.group_demo.agent;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 周计划长任务 Agent：把用户的一句话目标拆解成多个子任务并逐步执行，
 * 最终整合成一份完整的一周学习生活计划。
 *
 * <p>执行闭环：LLM 拆解子任务 -> 每个子任务先查 RAG，没资料再走 LLM
 * -> LLM 把所有子任务结果整合成按天排列的周计划。
 */
@Service
public class PlanAgentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAgentService.class);
    private static final int MAX_STEPS = 5;

    private final LlmService llmService;
    private final KeywordRagService ragService;

    public PlanAgentService(LlmService llmService, KeywordRagService ragService) {
        this.llmService = llmService;
        this.ragService = ragService;
    }

    public String run(String userId, String goal) {
        if (!llmService.isConfigured()) {
            return "还没有配置 llm.api-key，无法执行规划任务。请先配置后重试。";
        }
        log.info("PlanAgent 启动 userId={} goal={}", userId, goal);

        List<String> steps = decompose(goal);
        if (steps.isEmpty()) {
            return "这个目标太模糊了，请说得具体一点。例如：帮我制定下周兼顾课程、运动、社团和复习的安排。";
        }
        log.info("PlanAgent 拆解出 {} 个子任务：{}", steps.size(), steps);

        Map<String, String> stepResults = new LinkedHashMap<>();
        for (String step : steps) {
            String result = executeStep(userId, step);
            stepResults.put(step, result);
            log.info("PlanAgent 子任务完成：{}（结果 {} 字）", step, result.length());
        }

        String plan = integrate(goal, stepResults);
        log.info("PlanAgent 完成，最终产出 {} 字", plan.length());

        StringBuilder sb = new StringBuilder(plan);
        sb.append("\n\n———— Agent 执行过程 ————");
        int index = 1;
        for (Map.Entry<String, String> entry : stepResults.entrySet()) {
            sb.append("\n").append(index++).append(". ").append(entry.getKey()).append(" ✓");
        }
        return sb.toString();
    }

    private List<String> decompose(String goal) {
        String system = """
            你是任务规划助手。请把用户的目标拆解成 3~5 个具体的子任务，用于收集制定计划所需的信息。
            要求：
            - 每行一个子任务，格式为"1. 动词开头的具体任务"
            - 子任务应覆盖：查询课表、查询作业或考试安排、了解个人偏好（运动/社团/作息）等信息
            - 只输出子任务列表，不要输出任何其他内容
            """;
        try {
            String reply = llmService.chatRaw(system, goal);
            return parseSteps(reply);
        } catch (Exception e) {
            log.warn("任务拆解失败：{}", e.getMessage());
            return List.of();
        }
    }

    private List<String> parseSteps(String reply) {
        List<String> steps = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return steps;
        }
        for (String line : reply.split("\n")) {
            String step = line.trim();
            step = step.replaceFirst("^\\d+[.、)．]\\s*", "");
            if (step.length() > 3 && !step.startsWith("子任务")) {
                steps.add(step);
            }
        }
        return steps.size() > MAX_STEPS ? new ArrayList<>(steps.subList(0, MAX_STEPS)) : steps;
    }

    private String executeStep(String userId, String step) {
        try {
            if (ragService.isEnabled()) {
                List<KnowledgeChunk> hits = ragService.retrieve(step);
                if (!hits.isEmpty()) {
                    return llmService.chatRaw(ragService.buildEnhancedPrompt(hits), step);
                }
            }
            return llmService.chat(userId, step);
        } catch (Exception e) {
            log.warn("子任务执行失败：{} - {}", step, e.getMessage());
            return "（该子任务暂无可用信息）";
        }
    }

    private String integrate(String goal, Map<String, String> stepResults) {
        StringBuilder context = new StringBuilder();
        for (Map.Entry<String, String> entry : stepResults.entrySet()) {
            context.append("【子任务】").append(entry.getKey())
                .append("\n【结果】").append(entry.getValue()).append("\n\n");
        }
        String system = """
            你是学习规划助手。请根据用户的目标和已收集的信息，制定一份完整、可执行的一周安排。
            要求：
            1. 按周一到周日逐天列出时间安排（上课、自习、运动、社团、休息）
            2. 作业和复习任务要写明对应科目与时间段
            3. 信息不足的地方给出合理建议，不要编造具体事实
            4. 最后用一两句话总结本周重点
            直接输出计划内容，不要客套话。
            """;
        try {
            return llmService.chatRaw(system, "我的目标：" + goal + "\n\n已收集的信息：\n" + context);
        } catch (Exception e) {
            log.warn("计划整合失败：{}", e.getMessage());
            return "很抱歉，生成计划时出错了，请稍后再试。";
        }
    }
}
