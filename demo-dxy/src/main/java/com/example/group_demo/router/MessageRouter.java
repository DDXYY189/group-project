package com.example.group_demo.router;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.rag.KeywordRagService;
import com.example.group_demo.rag.KnowledgeChunk;
import com.example.group_demo.skill.Skill;
import com.example.group_demo.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 文本消息统一路由：
 * 命中 Skill 关键词 -> Skill 执行；
 * 后续接入 RAG 命中 -> 增强 Prompt -> LLM 回复；
 * 都没命中 -> LLM 工具对话兜底。
 */
@Service
public class MessageRouter {

    private static final Logger log = LoggerFactory.getLogger(MessageRouter.class);

    private final SkillRegistry skillRegistry;
    private final LlmService llmService;
    private final KeywordRagService ragService;

    public MessageRouter(SkillRegistry skillRegistry, LlmService llmService,
                         KeywordRagService ragService) {
        this.skillRegistry = skillRegistry;
        this.llmService = llmService;
        this.ragService = ragService;
    }

    public String route(String userId, String userText) {
        Skill skill = skillRegistry.match(userText);
        if (skill != null) {
            log.info("消息命中 Skill userId={} skill={}", userId, skill.name());
            return runSkill(userId, userText, skill);
        }
        List<KnowledgeChunk> hits = ragService.retrieve(userText);
        if (!hits.isEmpty()) {
            log.info("消息命中 RAG userId={} hits={}", userId, hits.size());
            return llmService.chatWithTools(
                userId, userText, ragService.buildEnhancedPrompt(hits), null);
        }
        return llmService.chatWithTools(userId, userText);
    }

    private String runSkill(String userId, String userText, Skill skill) {
        if (skill.directReply()) {
            String reply = skill.execute(userId, userText);
            llmService.recordTurn(userId, userText, reply);
            return reply;
        }
        return llmService.chatWithTools(userId, userText, skill.instructions(), skill.allowedTools());
    }
}
