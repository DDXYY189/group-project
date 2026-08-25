package com.example.demo_wkx.rag;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 极简关键词检索版 RAG 服务
 *
 * RAG (Retrieval-Augmented Generation) 核心思想：
 * 1. 检索：根据用户消息从知识库中查找相关文档
 * 2. 增强：将检索到的文档内容注入到 LLM 的 Prompt 中
 * 3. 生成：LLM 基于增强后的上下文生成更准确的回复
 *
 * 本实现采用极简关键词匹配（非向量检索），适合快速验证 RAG 流程。
 * 通过 rag.enabled 配置项可开启/关闭 RAG，用于对比测试。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    @Value("${rag.enabled:true}")
    private boolean enabled;

    private final List<Document> knowledgeBase = new ArrayList<>();

    /**
     * 知识库文档：每个文档包含关键词列表和内容
     * 关键词用于匹配用户消息，内容用于增强 LLM Prompt
     */
    private record Document(String id, String[] keywords, String content) {}

    @PostConstruct
    void init() {
        // 文档1: Spring Boot 框架
        knowledgeBase.add(new Document("doc_springboot",
            new String[]{"Spring Boot", "spring boot", "SpringBoot", "spring框架", "Java框架", "微服务"},
            "Spring Boot 是基于 Spring 框架的快速开发脚手架，核心特性包括自动配置、内嵌容器（Tomcat/Jetty）、"
            + "起步依赖（Starter）和生产就绪功能。本项目使用 Spring Boot 4.1.0 + Java 21 构建，"
            + "利用虚拟线程（Virtual Thread）实现轻量级并发处理微信消息。"
        ));

        // 文档2: Function Calling
        knowledgeBase.add(new Document("doc_fc",
            new String[]{"Function Calling", "function calling", "函数调用", "工具调用", "Tool Use", "function"},
            "Function Calling 是 LLM 的工具使用能力：模型根据用户意图判断是否需要调用外部函数，"
            + "输出结构化参数（JSON），由本地代码执行后返回结果给模型。本项目实现了 get_weather（天气查询）"
            + "和 get_current_time（时间查询）两个工具，LLM 通过 tool_calls 字段发起调用。"
        ));

        // 文档3: RAG 概念
        knowledgeBase.add(new Document("doc_rag",
            new String[]{"RAG", "检索增强", "检索增强生成", "RAG是什么", "知识库", "retrieval"},
            "RAG (Retrieval-Augmented Generation) 通过从知识库检索相关文档来增强 LLM 回答的准确性，"
            + "解决模型幻觉和领域知识不足的问题。流程：用户提问→检索相关文档→将文档注入Prompt→LLM生成回答。"
            + "检索方式包括关键词匹配（简单快速）和向量相似度检索（语义级，需 Embedding 模型）。"
        ));

        // 文档4: Skill 概念
        knowledgeBase.add(new Document("doc_skill",
            new String[]{"Skill", "skill", "技能", "自定义Skill", "关键词匹配", "关键词触发"},
            "Skill 是基于关键词匹配的确定性执行单元：当用户消息命中预设关键词时直接执行对应逻辑并返回结果，"
            + "无需 LLM 参与。与 Function Calling 的区别：Skill 是规则驱动（关键词匹配），响应快、可控；"
            + "Function Calling 是模型驱动（LLM 决策），灵活但依赖模型推理能力。"
        ));

        // 文档5: 微信 iLink Bot
        knowledgeBase.add(new Document("doc_ilink",
            new String[]{"微信", "Bot", "机器人", "iLink", "ilink", "微信机器人", "WeChat"},
            "本项目基于 wechat-ilink-sdk 2.3.3 开发微信 Bot。SDK 提供 ILinkClient 管理连接、"
            + "OnMessageListener 接收消息（支持文本/图片/语音），支持 sendText/sendImage/sendFile 发送消息。"
            + "语音消息通过 DashScope paraformer-v2 ASR 转文字，回复通过 CosyVoice TTS 合成 MP3 发送。"
        ));

        // 文档6: 通义千问模型
        knowledgeBase.add(new Document("doc_qwen",
            new String[]{"通义千问", "qwen", "Qwen", "千问", "DashScope", "阿里云", "大模型"},
            "通义千问 (Qwen) 是阿里云推出的开源大语言模型，通过 DashScope 平台提供 API 服务。"
            + "本项目使用 qwen-turbo 进行文本对话（base-url: dashscope.aliyuncs.com/compatible-mode/v1），"
            + "qwen-vl-plus 进行图片理解，CosyVoice (longfei_v3) 进行语音合成，paraformer-v2 进行语音识别。"
        ));

        // 文档7: 心知天气
        knowledgeBase.add(new Document("doc_weather",
            new String[]{"心知天气", "天气", "weather", "天气预报", "气温", "seniverse"},
            "心知天气 (Seniverse) 提供实时天气数据 API。本项目通过 api.seniverse.com/v3/weather/now.json "
            + "获取实时天气，daily.json 获取未来3天预报。备用方案包括 Open-Meteo（开源免费）和 wttr.in。"
            + "天气查询作为 Function Calling 工具被 LLM 自动调用。"
        ));

        log.info("📚 RAG 知识库加载完成，共 {} 篇文档，RAG 状态: {}", knowledgeBase.size(), enabled ? "开启" : "关闭");
    }

    /**
     * 从知识库检索与用户消息相关的文档
     * @return 拼接后的上下文文本，无匹配时返回 null
     */
    public String retrieve(String userMessage) {
        if (!enabled) {
            log.debug("🔍 RAG 已关闭，跳过检索");
            return null;
        }
        if (userMessage == null || userMessage.isBlank()) return null;

        List<Document> matched = new ArrayList<>();
        for (Document doc : knowledgeBase) {
            for (String keyword : doc.keywords()) {
                if (userMessage.toLowerCase().contains(keyword.toLowerCase())) {
                    matched.add(doc);
                    log.info("🔍 [RAG] 检索命中文档: {} (关键词: {})", doc.id(), keyword);
                    break;
                }
            }
        }

        if (matched.isEmpty()) {
            log.debug("🔍 [RAG] 未检索到相关文档");
            return null;
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\n【知识库参考信息】\n");
        for (Document doc : matched) {
            context.append("---\n").append(doc.content()).append("\n\n");
        }
        context.append("---\n请结合以上知识库信息回答用户问题。\n");
        return context.toString();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        log.info("📚 RAG 已{}", enabled ? "开启" : "关闭");
    }

    public int getDocumentCount() {
        return knowledgeBase.size();
    }
}
