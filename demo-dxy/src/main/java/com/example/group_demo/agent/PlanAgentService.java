package com.example.group_demo.agent;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.RagService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 长任务 Agent：把用户的一句话目标，拆解成多个子任务并逐步执行，
 * 最终整合成一份完整成品（一周学习生活计划）。
 *
 * 执行流程（对应"拆解-执行-整合"闭环）：
 *   第 1 步 LLM 拆解：把目标拆成 3~5 个子任务
 *   第 2 步 逐个执行：每个子任务先查 RAG（课表/作业等资料），
 *             没资料再走 LLM（注意：不走 Skill 层，避免"生成计划"类
 *             子任务再次触发 PlanSkill 造成无限递归）
 *   第 3 步 LLM 整合：把所有子任务结果合成一份按天排列的周计划
 */
@Service
public class PlanAgentService {

    private static final Logger log = LoggerFactory.getLogger(PlanAgentService.class);
    private static final int MAX_STEPS = 5;

    private final LlmService llmService;
    private final RagService ragService;

    public PlanAgentService(LlmService llmService, RagService ragService) {
        this.llmService = llmService;
        this.ragService = ragService;
    }

    /**
     * Agent 入口：输入一句目标，输出一份完整的周计划。
     */
    public String run(String userId, String goal) {
        if (!llmService.isConfigured()) {
            return "还没有配置 llm.api-key，无法执行规划任务。请先在 application-local.properties 里配置。";
        }
        log.info("PlanAgent 启动 userId={} goal={}", userId, goal);

        // ===== 第 1 步：任务拆解（LLM）=====
        List<String> steps = decompose(goal);
        if (steps.isEmpty()) {
            return "这个目标太模糊了，请说得具体一点。例如：帮我制定下周兼顾课程、运动、社团和复习的安排。";
        }
        log.info("PlanAgent 拆解出 {} 个子任务：{}", steps.size(), steps);

        // ===== 第 2 步：逐个执行子任务 =====
        Map<String, String> stepResults = new LinkedHashMap<>();
        for (String step : steps) {
            String result = executeStep(userId, step);
            stepResults.put(step, result);
            log.info("PlanAgent 子任务完成：{}（结果 {} 字）", step, result.length());
        }

        // ===== 第 3 步：整合成最终成品 =====
        String plan = integrate(goal, stepResults);
        log.info("PlanAgent 完成，最终产出 {} 字", plan.length());

        // 附上执行过程，体现 Agent 的"拆解-执行-整合"闭环
        StringBuilder sb = new StringBuilder();
        sb.append(plan).append("\n\n———— Agent 执行过程 ————");
        int i = 1;
        for (Map.Entry<String, String> e : stepResults.entrySet()) {
            sb.append("\n").append(i++).append(". ").append(e.getKey()).append(" ✓");
        }
        return sb.toString();
    }

    /**
     * 第 1 步：让 LLM 把目标拆解成子任务列表。
     */
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

    /**
     * 解析 LLM 返回的编号列表，去掉"1."、"2、"等前缀。
     */
    private List<String> parseSteps(String reply) {
        List<String> steps = new ArrayList<>();
        if (reply == null || reply.isBlank()) {
            return steps;
        }
        for (String line : reply.split("\n")) {
            String s = line.trim();
            s = s.replaceFirst("^\\d+[.、)．]\\s*", "");   // 去掉编号前缀
            if (s.length() > 3 && !s.startsWith("子任务")) {
                steps.add(s);
            }
        }
        return steps.size() > MAX_STEPS ? new ArrayList<>(steps.subList(0, MAX_STEPS)) : steps;
    }

    /**
     * 第 2 步：执行单个子任务。
     * 先 RAG（命中课表/作业等资料就直接用），没资料再走 LLM 通用回答。
     */
    private String executeStep(String userId, String step) {
        try {
            if (ragService.isEnabled()) {
                String rag = ragService.chatWithRag(userId, step);
                if (rag != null) {
                    return rag;
                }
            }
            return llmService.chat(userId, step);
        } catch (Exception e) {
            log.warn("子任务执行失败：{} - {}", step, e.getMessage());
            return "（该子任务暂无可用信息）";
        }
    }

    /**
     * 第 3 步：把目标 + 所有子任务结果交给 LLM，整合成最终周计划。
     */
    private String integrate(String goal, Map<String, String> stepResults) {
        StringBuilder context = new StringBuilder();
        for (Map.Entry<String, String> e : stepResults.entrySet()) {
            context.append("【子任务】").append(e.getKey())
                   .append("\n【结果】").append(e.getValue()).append("\n\n");
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
