package com.example.demo.agent;

import com.example.demo.llm.Decision;
import com.example.demo.llm.LlmService;
import com.example.demo.tool.Tool;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 机器人的“大脑”：先判断回复方式（文字/语音/图片），
 * 文字路径再走带工具调用的对话，让模型按需调用天气、计算器等工具。
 */
@Service
public class AgentService {

    private final LlmService llmService;
    private final List<Tool> tools;

    public AgentService(LlmService llmService, List<Tool> tools) {
        this.llmService = llmService;
        this.tools = tools;
    }

    public AgentResponse handle(String userText) {
        Decision decision = llmService.decide(userText);
        String intent = decision == null ? "text" : decision.intent();
        String content = decision == null ? "" : decision.content();

        if ("voice".equals(intent)) {
            return new AgentResponse(
                    "voice", StringUtils.hasText(content) ? content : userText);
        }
        if ("image".equals(intent)) {
            return new AgentResponse(
                    "image", StringUtils.hasText(content) ? content : userText);
        }

        String answer = llmService.runWithTools(userText, tools);
        return new AgentResponse("text", answer);
    }
}
