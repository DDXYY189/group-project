package com.example.group_demo.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简关键词检索版 RAG：
 * 启动时加载 knowledge 目录下的 Markdown 知识库，按段落切块，
 * 用中英文关键词构建倒排索引，查询时按命中次数返回 top-k 资料块。
 */
@Service
public class KeywordRagService {

    private static final Logger log = LoggerFactory.getLogger(KeywordRagService.class);
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]{2,}");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9]{2,}");

    private final RagProperties properties;
    private final List<KnowledgeChunk> chunks;
    private final Map<String, List<Integer>> index = new HashMap<>();
    private volatile boolean enabled;

    @Autowired
    public KeywordRagService(RagProperties properties) {
        this(properties, loadKnowledgeFromClasspath());
    }

    public KeywordRagService(RagProperties properties, List<KnowledgeChunk> chunks) {
        this.properties = properties;
        this.chunks = List.copyOf(chunks);
        this.enabled = properties.isEnabled();
        buildIndex();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int topK() {
        return Math.max(1, properties.getTopK());
    }

    public int chunkCount() {
        return chunks.size();
    }

    public List<KnowledgeChunk> retrieve(String query) {
        return retrieve(query, topK());
    }

    public List<KnowledgeChunk> retrieve(String query, int topK) {
        if (!enabled || query == null || query.isBlank()) {
            return List.of();
        }
        Map<Integer, Integer> scores = new HashMap<>();
        for (String token : tokenize(query)) {
            for (int chunkId : index.getOrDefault(token, List.of())) {
                scores.merge(chunkId, 1, Integer::sum);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, topK);
        return scores.entrySet().stream()
            .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                .thenComparing(Map.Entry.comparingByKey()))
            .limit(limit)
            .map(entry -> chunks.get(entry.getKey()))
            .toList();
    }

    public String buildEnhancedPrompt(List<KnowledgeChunk> hits) {
        StringBuilder sb = new StringBuilder(
            "以下是检索到的参考资料，请优先依据资料回答，不得编造；资料中没有答案时请如实说明。\n");
        int i = 1;
        for (KnowledgeChunk chunk : hits) {
            sb.append("\n[").append(i++).append("] ").append(chunk.title())
                .append("\n").append(chunk.content());
        }
        return sb.toString();
    }

    private void buildIndex() {
        for (int i = 0; i < chunks.size(); i++) {
            for (String token : tokenize(chunks.get(i).content())) {
                index.computeIfAbsent(token, key -> new ArrayList<>()).add(i);
            }
        }
    }

    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        String lower = text.toLowerCase();
        Matcher ascii = ASCII_WORD.matcher(lower);
        while (ascii.find()) {
            tokens.add(ascii.group());
        }
        Matcher cjk = CJK_RUN.matcher(lower);
        while (cjk.find()) {
            String run = cjk.group();
            for (int i = 0; i < run.length() - 1; i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens.stream().distinct().toList();
    }

    private static List<KnowledgeChunk> loadKnowledgeFromClasspath() {
        List<KnowledgeChunk> result = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath*:knowledge/*.md");
            for (Resource resource : resources) {
                String title = resource.getFilename();
                if (title != null && title.toLowerCase().endsWith(".md")) {
                    title = title.substring(0, title.length() - 3);
                }
                String content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                result.addAll(chunk(title, content));
            }
        } catch (IOException e) {
            log.warn("加载知识库失败，RAG 将返回空结果", e);
        }
        return result;
    }

    private static List<KnowledgeChunk> chunk(String title, String content) {
        List<KnowledgeChunk> result = new ArrayList<>();
        String[] paragraphs = content.split("\\R\\s*\\R");
        int index = 1;
        for (String paragraph : paragraphs) {
            String text = paragraph.trim();
            if (text.isBlank() || text.length() < 4) {
                continue;
            }
            result.add(new KnowledgeChunk(title + "-" + index, title, text));
            index++;
        }
        return result;
    }
}
