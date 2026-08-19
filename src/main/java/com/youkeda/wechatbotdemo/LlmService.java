package com.youkeda.wechatbotdemo;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 大模型服务：调用阿里云百炼（DashScope）的通义千问生成回复。
 * API Key 优先从环境变量 DASHSCOPE_API_KEY 读取，
 * 读不到再从 classpath 下的 application.properties 里读 dashscope.api-key。
 */
public class LlmService {

    private final String apiKey;
    private final Generation generation = new Generation();

    // Function Calling 用到的工具服务（复用已有 + 自定义）
    private final WeatherService weatherService;
    private final ImageService imageService;
    private final WordService wordService;

    public LlmService() {
        this.apiKey = loadApiKey();
        this.weatherService = new WeatherService();
        this.imageService = new ImageService(apiKey);
        this.wordService = new WordService();
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
                .content("你是一个友好的微信聊天机器人。\n"
                        + "如果用户要求你画画、生成图片或发送图片，你只输出一行：`[IMAGE:<图片提示词>]`，提示词用中文，不要有多余文字。\n"
                        + "如果用户要求你发语音、念出来或读出来，你只输出一行：`[VOICE:<要朗读的内容>]`，内容用中文，不要有多余文字。\n"
                        + "其他情况下，请简洁自然地回复，控制在100字以内。")
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

    /**
     * Function Calling 对话（任务三核心）：
     * 1. 用户消息 + 工具清单一起发给大模型；
     * 2. 大模型决定是否调用工具（返回 tool_calls，而不是普通文字）；
     * 3. 应用执行对应工具（查单词 / 随机数 / 天气 / 图片）；
     * 4. 把工具结果连同历史消息回传大模型，生成最终自然语言回复。
     */
    public ChatResult chatWithTools(String userText) throws Exception {
        Message systemMsg = Message.builder()
                .role(Role.SYSTEM.getValue())
                .content("你是一个微信聊天机器人，拥有查单词、随机数、查天气、生成图片等工具。"
                        + "当用户的需求匹配某个工具时，请调用对应工具；工具结果返回后，用简洁自然的中文回复用户。")
                .build();
        Message userMsg = Message.builder()
                .role(Role.USER.getValue())
                .content(userText)
                .build();

        // 第一轮：用户消息 + 工具清单一起发给 qwen
        GenerationResult first = generation.call(GenerationParam.builder()
                .model("qwen-turbo")
                .messages(Arrays.asList(systemMsg, userMsg))
                .tools(ToolDefinitions.allTools())   // ← 关键：带上工具清单
                .apiKey(apiKey)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build());

        Message assistantMsg = first.getOutput().getChoices().get(0).getMessage();
        List<ToolCallBase> calls = assistantMsg.getToolCalls();

        // 大模型没想用工具 → 直接返回文字（普通聊天）
        if (calls == null || calls.isEmpty()) {
            return new ChatResult(assistantMsg.getContent(), null);
        }

        // 大模型想调工具 → 执行（第一版只处理第一个调用）
        ToolCallFunction call = (ToolCallFunction) calls.get(0);
        String name = call.getFunction().getName();
        String argsJson = call.getFunction().getArguments();
        byte[] imageBytes = null;
        String toolResult;

        switch (name) {
            case "lookup_word":
                toolResult = wordService.lookup(parseArg(argsJson, "word"));
                break;
            case "generate_random":
                JsonObject args = JsonParser.parseString(argsJson).getAsJsonObject();
                int min = args.has("min") ? args.get("min").getAsInt() : 1;
                int max = args.has("max") ? args.get("max").getAsInt() : 100;
                if (min > max) {
                    int t = min;
                    min = max;
                    max = t;
                }
                int num = ThreadLocalRandom.current().nextInt(min, max + 1);
                toolResult = "随机数结果：" + num + "（范围 " + min + "~" + max + "）";
                break;
            case "get_weather":
                toolResult = weatherService.queryWeather(parseArg(argsJson, "city"));
                break;
            case "generate_image":
                String prompt = parseArg(argsJson, "prompt");
                imageBytes = imageService.generateImage(prompt);   // 图片直接生成好，应用负责发送
                toolResult = "图片已生成成功";
                break;
            default:
                toolResult = "未知工具：" + name;
        }
        System.out.println("工具执行 [" + name + "]: " + toolResult);

        // 第二轮：把「assistant 的 tool_calls」+「工具结果」一起回传
        Message toolMsg = Message.builder()
                .role(Role.TOOL.getValue())
                .toolCallId(call.getId())   // 必须带上，关联是哪次调用
                .name(name)
                .content(toolResult)
                .build();

        GenerationResult second = generation.call(GenerationParam.builder()
                .model("qwen-turbo")
                .messages(Arrays.asList(systemMsg, userMsg, assistantMsg, toolMsg))
                .apiKey(apiKey)
                .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                .build());

        String finalText = second.getOutput().getChoices().get(0).getMessage().getContent();
        return new ChatResult(finalText, imageBytes);
    }

    /** 大模型回复结果：文字 + 可选图片（生成图片工具执行成功后不为 null） */
    public static class ChatResult {
        public final String text;
        public final byte[] imageBytes;

        public ChatResult(String text, byte[] imageBytes) {
            this.text = text;
            this.imageBytes = imageBytes;
        }
    }

    /** 从工具参数的 JSON 里取字符串，缺了返回空串 */
    private static String parseArg(String argsJson, String key) {
        JsonObject obj = JsonParser.parseString(argsJson).getAsJsonObject();
        return obj.has(key) ? obj.get(key).getAsString() : "";
    }

    /** 获取 API Key（用于图片等其他服务复用同一份密钥） */
    public String getApiKey() {
        return apiKey;
    }

    /** 打日志时把 key 打码，避免泄露 */
    private static String mask(String key) {
        if (key == null || key.length() < 10) {
            return "****";
        }
        return key.substring(0, 6) + "****" + key.substring(key.length() - 4);
    }
}
