package com.example.group_demo.bot;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.RagService;
import com.example.group_demo.tool.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 三层消息路由器：
 * 第 1 层 Skill 关键词路由（快，零成本）→
 * 第 2 层 RAG 检索增强（资料问答）→
 * 第 3 层 LLM + Function Calling 兜底（现有逻辑）
 */
@Service
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final SkillRegistry skillRegistry;
    private final RagService ragService;
    private final LlmService llmService;

    public MessageRouter(SkillRegistry skillRegistry, RagService ragService, LlmService llmService) {
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
        this.llmService = llmService;
    }

    /**
     * 路由一条文本消息，返回要回复的内容（不会返回 null）。
     */
    public String route(String userId, String text) {
        // 第 1 层：Skill 关键词路由，命中直接执行，不走 LLM
        String skillResult = skillRegistry.execute(userId, text);
        if (skillResult != null) {
            log.info("路由[1-Skill] 命中 userId={} text={}", userId, text);
            return skillResult;
        }

        // 第 2 层：RAG 检索增强，有资料才回答，无资料交给下一层
        if (ragService.isEnabled()) {
            String ragResult = ragService.chatWithRag(userId, text);
            if (ragResult != null) {
                log.info("路由[2-RAG] 命中 userId={} text={}", userId, text);
                return ragResult;
            }
        } else {
            log.info("路由[2-RAG] 已关闭（rag.enabled=false）");
        }

        // 第 3 层：LLM + Function Calling 兜底
        if (llmService.isConfigured()) {
            try {
                return llmService.chatWithTools(userId, text);
            } catch (Exception e) {
                log.warn("路由[3-LLM] 调用失败，回退为回显：{}", e.getMessage());
            }
        }
        return "收到：" + text;
    }
}
