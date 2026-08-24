package com.example.group_demo.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 极简关键词检索版 RAG 服务。
 * <p>
 * 不使用向量库和 embedding 模型，纯关键词匹配。
 * 知识库格式：每条知识用 "===" 分隔，第一行以 "关键词：" 开头（多个关键词用逗号分隔），
 * 后续行是知识内容。
 * </p>
 *
 * <pre>
 * 关键词：关键词1,关键词2
 * 知识内容第一行
 * 知识内容第二行
 * ===
 * 关键词：关键词3
 * 另一条知识内容
 * </pre>
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final String ENTRY_SEPARATOR = "===";
    private static final String KEYWORD_PREFIX = "关键词：";

    private final RagProperties properties;
    private final List<KnowledgeEntry> knowledgeBase = new ArrayList<>();

    public RagService(RagProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (!properties.isEnableRag()) {
            log.info("RAG 开关已关闭，跳过知识库加载");
            return;
        }
        loadKnowledgeBase();
    }

    /**
     * 加载知识库文件到内存。
     */
    private void loadKnowledgeBase() {
        String path = properties.getKnowledgeBasePath();
        if (path == null || path.isBlank()) {
            log.warn("知识库路径未配置，RAG 知识库为空");
            return;
        }
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) {
                log.warn("知识库文件不存在: {}, RAG 知识库为空", path);
                return;
            }
            String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
            parseKnowledgeEntries(content);
            log.info("RAG 知识库加载完成，共 {} 条知识, 路径: {}", knowledgeBase.size(), path);
        } catch (IOException e) {
            log.error("加载知识库文件失败 path={}", path, e);
        }
    }

    /**
     * 解析知识库文本为条目列表。
     */
    private void parseKnowledgeEntries(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        String[] entries = content.split(ENTRY_SEPARATOR);
        for (String entryText : entries) {
            String trimmed = entryText.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            KnowledgeEntry entry = parseEntry(trimmed);
            if (entry != null) {
                knowledgeBase.add(entry);
            }
        }
    }

    private KnowledgeEntry parseEntry(String text) {
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0) {
            return null;
        }
        String firstLine = lines[0].trim();
        if (!firstLine.startsWith(KEYWORD_PREFIX)) {
            return null;
        }
        String keywordsStr = firstLine.substring(KEYWORD_PREFIX.length()).trim();
        String[] keywords = keywordsStr.split("[，,、]");
        List<String> keywordList = new ArrayList<>();
        for (String kw : keywords) {
            String trimmed = kw.trim();
            if (!trimmed.isEmpty()) {
                keywordList.add(trimmed);
            }
        }
        StringBuilder contentSb = new StringBuilder();
        for (int i = 1; i < lines.length; i++) {
            if (contentSb.length() > 0) {
                contentSb.append("\n");
            }
            contentSb.append(lines[i]);
        }
        return new KnowledgeEntry(keywordList, contentSb.toString().trim());
    }

    /**
     * 根据用户输入进行关键词检索，返回匹配的知识片段。
     *
     * @param userText 用户输入
     * @return 匹配到的知识片段列表，未命中返回空列表
     */
    public List<String> search(String userText) {
        if (!properties.isEnableRag()) {
            return List.of();
        }
        if (userText == null || userText.isBlank()) {
            return List.of();
        }
        if (knowledgeBase.isEmpty()) {
            return List.of();
        }
        String text = userText.trim();
        List<String> results = new ArrayList<>();
        int maxResults = Math.max(1, properties.getMaxResults());
        for (KnowledgeEntry entry : knowledgeBase) {
            if (results.size() >= maxResults) {
                break;
            }
            for (String keyword : entry.keywords()) {
                if (text.contains(keyword)) {
                    results.add(entry.content());
                    log.debug("RAG 命中关键词: keyword={}", keyword);
                    break; // 一条知识只要命中一个关键词就算
                }
            }
        }
        if (!results.isEmpty()) {
            log.info("RAG 检索完成，命中 {} 条知识", results.size());
        }
        return results;
    }

    /**
     * RAG 是否开启。
     */
    public boolean isEnabled() {
        return properties.isEnableRag();
    }

    /**
     * 将检索到的知识片段格式化为增强 Prompt 文本。
     */
    public String buildAugmentedPrompt(List<String> knowledgeFragments) {
        if (knowledgeFragments == null || knowledgeFragments.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("以下是从知识库中检索到的参考信息，请结合这些信息回答用户问题：\n");
        for (int i = 0; i < knowledgeFragments.size(); i++) {
            sb.append("【参考").append(i + 1).append("】\n");
            sb.append(knowledgeFragments.get(i)).append("\n");
        }
        sb.append("\n请基于以上参考信息回答用户问题，如果参考信息中没有答案，就用你自己的知识回答。");
        return sb.toString();
    }

    /**
     * 知识库条目。
     */
    private record KnowledgeEntry(List<String> keywords, String content) {
    }
}
