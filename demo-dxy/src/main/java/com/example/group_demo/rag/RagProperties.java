package com.example.group_demo.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 模块配置属性。
 */
@Component
@ConfigurationProperties(prefix = "rag")
public class RagProperties {

    /**
     * RAG 全局开关：true 开启，false 关闭（跳过全部 RAG 逻辑）
     */
    private boolean enableRag = true;

    /**
     * 知识库文件路径（classpath 相对路径）
     */
    private String knowledgeBasePath = "rag/knowledge-base.txt";

    /**
     * 最多返回多少条匹配的知识片段
     */
    private int maxResults = 3;

    public boolean isEnableRag() {
        return enableRag;
    }

    public void setEnableRag(boolean enableRag) {
        this.enableRag = enableRag;
    }

    public String getKnowledgeBasePath() {
        return knowledgeBasePath;
    }

    public void setKnowledgeBasePath(String knowledgeBasePath) {
        this.knowledgeBasePath = knowledgeBasePath;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }
}
