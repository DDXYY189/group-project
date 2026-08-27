package com.example.group_demo.rag;

import com.example.group_demo.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 极简版 RAG（检索增强生成）：
 * 1. 准备一个内置文档库（Q&A 片段）
 * 2. 用户提问时按关键词检索相关文档
 * 3. 把检索到的文档拼进 System Prompt，让 LLM 看着资料回答
 *
 * 可通过配置 rag.enabled=false 关闭（用于开关对比测试）。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int TOP_N = 3;

    /** 每个文档带一组检索关键词和回答内容 */
    private record Doc(String keywords, String content) {
    }

    private final List<Doc> documents = List.of(
        // ===== 周计划 Agent 的知识库 =====
        new Doc("课表 课程 上课 周一 周二 周三 周四 周五 教室 高数 英语 大物 编程 数据结构",
                "大二下周课表：周一 8:00-9:40 高等数学（教1-201）、14:00-15:40 大学英语；"
                    + "周二 10:00-11:40 大学物理、16:00-17:40 Java 程序设计；"
                    + "周三全天无课；周四 8:00-9:40 高等数学、14:00-15:40 数据结构；"
                    + "周五 10:00-11:40 大学英语，下午无课。"),
        new Doc("作业 截止 deadline 提交 报告 考试 复习 小测",
                "下周作业与考试安排：高等数学作业周五 24:00 前提交；大学英语作文周三 22:00 前提交；"
                    + "大学物理实验报告周日 18:00 前提交；数据结构下下周三有小测，建议本周开始复习。"),
        // ===== 通用校园/生活 Q&A =====
        new Doc("寒假 暑假 寒暑假 假期 放假 开学 校历", "大二寒暑假：寒假约 40 天、暑假约 60 天，具体以校历为准；假期留校可去图书馆自习，开放时间 8:00-22:00。"),
        new Doc("wifi 校园网 密码 上网 连不上", "校园网：账号是学号，初始密码是身份证后六位，连不上时去信息中心 1 楼服务台重置。"),
        new Doc("上班 时间 考勤 迟到", "上班时间：周一到周五 9:00-18:00，午休 12:00-13:00。"),
        new Doc("午餐 食堂 吃饭 饭堂", "公司食堂在 3 楼，午餐时间 12:00-13:00，刷工卡就餐，自助取餐。"),
        new Doc("快递 收发 邮寄 取件", "快递收发室在 1 楼前台，工作日 9:00-18:00 可凭取件码取件。"),
        new Doc("报销 发票 出差", "报销流程：线上提交报销单，附发票照片，审批通过后 7 个工作日内到账。")
    );

    private final LlmService llmService;

    /** rag.enabled 开关，可在 application-local.properties 里改 */
    @Value("${rag.enabled:true}")
    private boolean enabled;

    public RagService(LlmService llmService) {
        this.llmService = llmService;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 关键词检索：统计每个文档命中用户文本中关键词的个数，取 Top 3。
     */
    public List<Doc> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return documents.stream()
            .map(doc -> {
                int score = 0;
                for (String keyword : doc.keywords().split(" ")) {
                    if (query.contains(keyword)) {
                        score++;
                    }
                }
                return Map.entry(doc, score);
            })
            .filter(entry -> entry.getValue() > 0)
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(TOP_N)
            .map(Map.Entry::getKey)
            .toList();
    }

    /**
     * 带 RAG 的对话：检索文档 → 拼进 System Prompt → 调 LLM。
     * 没检索到相关文档或 LLM 不可用时返回 null，交给下一层路由处理。
     */
    public String chatWithRag(String userId, String userText) {
        List<Doc> results = search(userText);
        if (results.isEmpty()) {
            return null;
        }
        if (!llmService.isConfigured()) {
            return null;
        }
        StringBuilder reference = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            reference.append(i + 1).append(". ").append(results.get(i).content()).append("\n");
        }
        String systemPrompt = "你是微信机器人助手，请用简洁的中文回答问题。"
            + "请优先参考以下资料回答，资料中没有的信息请如实说明，不要编造。\n\n"
            + "参考资料：\n" + reference;
        try {
            log.info("RAG 命中文档数={} query={}", results.size(), userText);
            return llmService.chatRaw(systemPrompt, userText);
        } catch (Exception e) {
            log.warn("RAG 调用 LLM 失败：{}", e.getMessage());
            return null;
        }
    }
}
