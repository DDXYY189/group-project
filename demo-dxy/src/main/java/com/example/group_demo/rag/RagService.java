package com.example.group_demo.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RagProperties properties;
    private final KnowledgeBase knowledgeBase;

    public RagService(RagProperties properties, KnowledgeBase knowledgeBase) {
        this.properties = properties;
        this.knowledgeBase = knowledgeBase;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public boolean shouldRetrieve(String userText) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (properties.getKeywords() == null || properties.getKeywords().isEmpty()) {
            return true;
        }
        return properties.getKeywords().stream().anyMatch(userText::contains);
    }

    public String augmentPrompt(String userText) {
        return augmentPrompt(null, userText);
    }

    public String augmentPrompt(String baseSystemPrompt, String userText) {
        if (!properties.isEnabled()) {
            return baseSystemPrompt;
        }
        List<KnowledgeDocument> docs = knowledgeBase.search(userText);
        if (docs.isEmpty()) {
            log.info("RAG 检索无命中，不增强 Prompt");
            return baseSystemPrompt;
        }
        StringBuilder sb = new StringBuilder();
        if (baseSystemPrompt != null && !baseSystemPrompt.isBlank()) {
            sb.append(baseSystemPrompt).append("\n\n");
        }
        sb.append("以下是从知识库中检索到的相关信息，请参考这些信息回答用户问题：\n\n");
        for (KnowledgeDocument doc : docs) {
            sb.append("【").append(doc.title()).append("】\n");
            sb.append(doc.content()).append("\n\n");
        }
        log.info("RAG 检索命中 {} 篇文档，已增强 Prompt", docs.size());
        return sb.toString();
    }
}
