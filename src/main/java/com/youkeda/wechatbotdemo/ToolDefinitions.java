package com.youkeda.wechatbotdemo;

import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolFunction;
import com.google.gson.JsonParser;

import java.util.Arrays;
import java.util.List;

/**
 * 机器人可用工具清单（Function Calling / Tool Use）。
 * 每个工具 = 一份 JSON Schema 描述，告诉大模型：工具叫什么、干嘛的、要什么参数。
 * 想删掉某个工具，只需在 allTools() 里去掉对应那一行。
 */
public class ToolDefinitions {

    /** 返回所有工具描述，传给大模型（GenerationParam.tools 需要 List<ToolBase>） */
    public static List<ToolBase> allTools() {
        return Arrays.<ToolBase>asList(
                wordTool(),      // 自定义工具 1：查单词
                randomTool(),    // 自定义工具 2：随机数
                weatherTool(),   // 已有工具 3：查天气
                imageTool());    // 已有工具 4：生成图片
    }

    /** 工具 1：查单词（自定义） */
    private static ToolFunction wordTool() {
        return ToolFunction.builder()
                .function(FunctionDefinition.builder()
                        .name("lookup_word")
                        .description("查询英文单词的中文释义，用户问某个单词是什么意思、怎么翻译时使用")
                        .parameters(JsonParser.parseString("{"
                                + "\"type\":\"object\","
                                + "\"properties\":{"
                                + "  \"word\":{\"type\":\"string\",\"minLength\":1,\"description\":\"要查询的英文单词，如 hello\"}"
                                + "},"
                                + "\"required\":[\"word\"]"
                                + "}").getAsJsonObject())
                        .build())
                .build();
    }

    /** 工具 2：随机数（自定义） */
    private static ToolFunction randomTool() {
        return ToolFunction.builder()
                .function(FunctionDefinition.builder()
                        .name("generate_random")
                        .description("生成一个指定范围内的随机整数，用户要求随机数、抽奖、抽签、掷骰子时使用")
                        .parameters(JsonParser.parseString("{"
                                + "\"type\":\"object\","
                                + "\"properties\":{"
                                + "  \"min\":{\"type\":\"integer\",\"description\":\"范围最小值，含边界，默认 1\"},"
                                + "  \"max\":{\"type\":\"integer\",\"description\":\"范围最大值，含边界，默认 100\"}"
                                + "},"
                                + "\"required\":[]"
                                + "}").getAsJsonObject())
                        .build())
                .build();
    }

    /** 工具 3：查天气（复用已有 WeatherService） */
    private static ToolFunction weatherTool() {
        return ToolFunction.builder()
                .function(FunctionDefinition.builder()
                        .name("get_weather")
                        .description("查询指定城市的实时天气，用户问\"xx天气\"时使用")
                        .parameters(JsonParser.parseString("{"
                                + "\"type\":\"object\","
                                + "\"properties\":{"
                                + "  \"city\":{\"type\":\"string\",\"description\":\"城市中文名，如：杭州、北京、临汾\"}"
                                + "},"
                                + "\"required\":[\"city\"]"
                                + "}").getAsJsonObject())
                        .build())
                .build();
    }

    /** 工具 4：生成图片（复用已有 ImageService） */
    private static ToolFunction imageTool() {
        return ToolFunction.builder()
                .function(FunctionDefinition.builder()
                        .name("generate_image")
                        .description("根据描述生成一张图片，用户要求画画、出图时使用")
                        .parameters(JsonParser.parseString("{"
                                + "\"type\":\"object\","
                                + "\"properties\":{"
                                + "  \"prompt\":{\"type\":\"string\",\"minLength\":1,\"description\":\"图片内容的中文描述\"},"
                                + "  \"size\":{\"type\":\"string\",\"enum\":[\"1024*1024\",\"720*1280\"],\"description\":\"图片尺寸，默认1024*1024\"}"
                                + "},"
                                + "\"required\":[\"prompt\"]"
                                + "}").getAsJsonObject())
                        .build())
                .build();
    }
}
