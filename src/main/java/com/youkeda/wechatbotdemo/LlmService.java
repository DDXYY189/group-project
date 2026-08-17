package com.youkeda.wechatbotdemo;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

/**
 * 大模型服务：调用阿里云百炼（DashScope）的通义千问生成回复。
 * API Key 优先从环境变量 DASHSCOPE_API_KEY 读取，
 * 读不到再从 classpath 下的 application.properties 里读 dashscope.api-key。
 */
public class LlmService {

    private final String apiKey;
    private final Generation generation = new Generation();

    public LlmService() {
        this.apiKey = loadApiKey();
        System.out.println("LLM 服务初始化完成，API Key = " + mask(apiKey));
    }

    private String loadApiKey() {
        // 1. 先尝试环境变量
        String key = System.getenv("DASHSCOPE_API_KEY");
        if (key != null && !key.isBlank()) {
            return key.trim();
        }

        // 2. 再尝试 application.properties
        Properties props = new Properties();
        try (InputStream in = LlmService.class.getClassLoader()
                .getResourceAsStream("application.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 application.properties 失败: " + e.getMessage(), e);
        }

        key = props.getProperty("dashscope.api-key", "");
        if (key.isBlank() || key.contains("REPLACE")) {
            throw new IllegalStateException(
                    "未配置 API Key：请在环境变量 DASHSCOPE_API_KEY，"
                    + "或 application.properties 的 dashscope.api-key 中填入你的百炼 API Key");
        }
        return key.trim();
    }

    /**
     * 把用户发来的文本交给通义千问，返回模型的回复文本。
     */
    public String chat(String userText) throws Exception {
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("你是一个友好的微信聊天机器人，回复要简洁自然，控制在100字以内。")
                .build();

        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(userText)
                .build();

        GenerationParam param = GenerationParam.builder()
                .model("qwen-turbo")
                .messages(Arrays.asList(systemMsg, userMsg))
                .apiKey(apiKey)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build();

        GenerationResult result = generation.call(param);
        return result.getOutput().getChoices().get(0).getMessage().getContent();
    }

    /** 打日志时把 key 打码，避免泄露 */
    private static String mask(String key) {
        if (key == null || key.length() < 10) {
            return "****";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
