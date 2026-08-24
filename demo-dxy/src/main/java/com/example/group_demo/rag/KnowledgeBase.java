package com.example.group_demo.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class KnowledgeBase {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBase.class);

    private final List<KnowledgeDocument> documents = new ArrayList<>();

    @PostConstruct
    public void load() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:knowledge/*.txt");
            for (Resource resource : resources) {
                try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String title = null;
                    String keywordLine = null;
                    StringBuilder content = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (title == null && line.startsWith("# ")) {
                            title = line.substring(2).trim();
                        } else if (keywordLine == null && line.startsWith("keywords:")) {
                            keywordLine = line.substring("keywords:".length()).trim();
                        } else {
                            content.append(line).append("\n");
                        }
                    }
                    if (title == null) {
                        title = resource.getFilename();
                    }
                    List<String> keywords = List.of();
                    if (keywordLine != null) {
                        keywords = List.of(keywordLine.split("[,，|]"));
                        keywords = keywords.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
                    }
                    documents.add(new KnowledgeDocument(title, content.toString().trim(), keywords));
                    log.info("加载知识文档: {} keywords={}", title, keywords);
                }
            }
            log.info("知识库加载完成，共 {} 篇文档", documents.size());
        } catch (Exception e) {
            log.warn("知识库加载失败，RAG 检索将返回空结果", e);
        }
    }

    public List<KnowledgeDocument> search(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<KnowledgeDocument> matched = new ArrayList<>();
        for (KnowledgeDocument doc : documents) {
            boolean hit = false;
            for (String keyword : doc.keywords()) {
                if (query.contains(keyword)) {
                    hit = true;
                    break;
                }
            }
            if (hit) {
                matched.add(doc);
            }
        }
        return matched;
    }

    public int size() {
        return documents.size();
    }
}
