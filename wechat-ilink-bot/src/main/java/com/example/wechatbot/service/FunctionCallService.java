package com.example.wechatbot.service;

import com.alibaba.dashscope.aigc.conversation.ConversationParam.ResultFormat;
import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationOutput.Choice;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.tools.FunctionDefinition;
import com.alibaba.dashscope.tools.ToolBase;
import com.alibaba.dashscope.tools.ToolCallBase;
import com.alibaba.dashscope.tools.ToolCallFunction;
import com.alibaba.dashscope.tools.ToolFunction;
import com.alibaba.dashscope.utils.JsonUtils;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Function Calling 服务
 *
 * 使用阿里 DashScope Java SDK 的 ToolFunction / FunctionDefinition 注册工具（禁止手写 JSON-Schema 字符串），
 * 通过 Generation 完成完整 Function-Calling 链路：
 *
 *   微信接收用户消息
 *     → 大模型自动判断是否需要调用工具
 *     → 执行本地 Java 方法（getZoneTime / dateToWeekday / getSentimentAnalysis）
 *     → 工具结果回传给大模型
 *     → 大模型组装自然语言回答
 *     → 返回微信
 *
 * 已注册的三个工具：
 *   1. getZoneTime   — 传入时区参数，返回该时区当前的日期、时间、星期；不传参数默认返回本机当前时间
 *   2. dateToWeekday — 传入 yyyy-MM-dd 格式日期字符串，输出该日期对应的星期几
 *   3. getSentimentAnalysis — 传入文本，调用阿里云百炼大模型进行语义级情感分析，返回整体情感类别、情绪细分、置信度及理由
 *
 * 【演示提问】
 *   示例 1：现在几点          → 大模型调用 getZoneTime（无参数），返回本机时间
 *           纽约现在几点      → 大模型调用 getZoneTime（timezone=America/New_York），返回纽约时间
 *   示例 2：2026-10-01是星期几 → 大模型调用 dateToWeekday（date=2026-10-01），返回星期四
 *   示例 3：分析这句话的情感：这破产品太垃圾了 → 大模型调用 getSentimentAnalysis，返回消极/抱怨
 */
@Service
public class FunctionCallService {

    private static final Logger log = LoggerFactory.getLogger(FunctionCallService.class);

    /** DashScope API Key，从配置/环境变量读取，严禁硬编码 */
    @Value("${llm.api-key}")
    private String apiKey;

    /** 文本对话模型，默认 qwen-plus（qwen-turbo 对 Function Calling 支持不稳定，易跳过工具调用直接编造答案） */
    @Value("${llm.text-model:qwen-plus}")
    private String textModel;

    /** 系统提示词：告知大模型具备工具能力，遇到时间/星期/情感分析问题主动调用工具 */
    private static final String SYSTEM_PROMPT =
            "你是一个智能微信助手。请用简洁友好的中文回复用户，回复不超过200字。" +
            "你可以使用以下工具：\n" +
            "1. getZoneTime：获取指定时区的当前日期、时间和星期，不传时区参数则返回本机当前时间\n" +
            "2. dateToWeekday：查询指定日期（yyyy-MM-dd格式）是星期几\n" +
            "3. getSentimentAnalysis：对文本进行情感分析，返回整体情感类别（积极/中性/消极）、情绪细分、置信度及理由\n" +
            "【强制规则】当用户询问当前时间、某个时区/城市的时间、某天是星期几等问题时，" +
            "必须调用相应工具获取准确信息，严禁自行猜测或编造时间。" +
            "当用户要求分析某段文本的情感、判断情绪倾向、检测文本态度时，" +
            "必须调用 getSentimentAnalysis 工具完成分析，由该工具返回的结论为准，严禁自行主观臆断。" +
            "常见时区映射：北京/上海/中国→Asia/Shanghai，纽约→America/New_York，伦敦→Europe/London，东京→Asia/Tokyo。";

    // ======================== 工具方法（Java 原生实现） ========================

    /**
     * 工具 1：获取指定时区的当前日期、时间和星期
     * Java 原生实现，使用 java.time.ZonedDateTime / ZoneId，不引入额外第三方依赖
     *
     * @param timezone 时区标识符，如 Asia/Shanghai、America/New_York；为 null 或空则返回本机当前时间
     * @return 格式化后的时间信息字符串
     */
    public String getZoneTime(String timezone) {
        ZonedDateTime now;
        String zoneDesc;

        if (timezone == null || timezone.trim().isEmpty()) {
            // 不传参数，返回本机当前时间
            now = ZonedDateTime.now();
            zoneDesc = now.getZone().getId();
        } else {
            try {
                ZoneId zoneId = ZoneId.of(timezone.trim());
                now = ZonedDateTime.now(zoneId);
                zoneDesc = zoneId.getId();
            } catch (Exception e) {
                return "无效的时区: " + timezone + "，请使用如 Asia/Shanghai、America/New_York、Europe/London 等时区标识符";
            }
        }

        String date = now.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String time = now.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        String weekday = toChineseWeekday(now.getDayOfWeek());

        return String.format("时区: %s, 日期: %s, 时间: %s, %s", zoneDesc, date, time, weekday);
    }

    /**
     * 工具 2：公历日期转星期
     * Java 原生实现，使用 java.time.LocalDate / DayOfWeek，不引入额外第三方依赖
     *
     * @param dateStr 日期字符串，格式 yyyy-MM-dd，如 2026-10-01
     * @return 该日期对应的星期几
     */
    public String dateToWeekday(String dateStr) {
        if (dateStr == null || dateStr.trim().isEmpty()) {
            return "日期不能为空，请使用 yyyy-MM-dd 格式，如 2026-10-01";
        }
        try {
            LocalDate date = LocalDate.parse(dateStr.trim(), DateTimeFormatter.ISO_LOCAL_DATE);
            String weekday = toChineseWeekday(date.getDayOfWeek());
            return dateStr + " 是" + weekday;
        } catch (Exception e) {
            return "日期格式错误: " + dateStr + "，请使用 yyyy-MM-dd 格式，如 2026-10-01";
        }
    }

    /**
     * DayOfWeek 枚举转中文星期
     */
    private String toChineseWeekday(DayOfWeek dayOfWeek) {
        switch (dayOfWeek) {
            case MONDAY:    return "星期一";
            case TUESDAY:   return "星期二";
            case WEDNESDAY: return "星期三";
            case THURSDAY:  return "星期四";
            case FRIDAY:    return "星期五";
            case SATURDAY:  return "星期六";
            case SUNDAY:    return "星期日";
            default:        return "未知";
        }
    }

    /**
     * 工具 3：文本情感分析
     * 调用阿里云百炼（DashScope）大模型进行语义级情感分析，复用已注入的 apiKey/textModel。
     * 不再使用本地关键词词典（词典无法覆盖「笨」「蠢」等无限词汇，易误判为中性）。
     *
     * 输出精细结果：
     *   - 整体情感类别：积极 / 中性 / 消极
     *   - 情绪细分：开心、夸赞、感谢、生气、抱怨、失望、担忧、嘲讽、平淡 等
     *   - 情绪置信度：0-100
     *   - 简短理由：列出文本中支撑该判断的关键词
     *
     * 实现要点：
     *   1. 用专门的情感分析系统提示词，强制大模型只输出严格 JSON（不带 markdown 代码块）。
     *   2. 本方法解析 JSON 后重新格式化为稳定字符串回传给外层大模型，避免格式漂移。
     *   3. API-Key 复用 @Value("${llm.api-key}")（即环境变量 DASHSCOPE_API_KEY），不新增密钥、不硬编码。
     *
     * @param text 待分析文本
     * @return 情感分析结果字符串
     */
    public String getSentimentAnalysis(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "待分析文本为空，无法进行情感分析";
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("DashScope API Key 未配置, 无法调用大模型进行情感分析");
            return "[API Key 未配置, 无法调用大模型进行情感分析, 请设置环境变量 DASHSCOPE_API_KEY]";
        }

        try {
            Generation gen = new Generation();
            List<Message> messages = new ArrayList<>();
            messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SENTIMENT_SYSTEM_PROMPT).build());
            messages.add(Message.builder().role(Role.USER.getValue()).content(text).build());

            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(textModel)
                    .messages(messages)
                    .resultFormat(ResultFormat.MESSAGE)
                    .build();

            log.info("情感分析调用大模型: text={}", text);
            GenerationResult result = gen.call(param);
            String content = result.getOutput().getChoices().get(0).getMessage().getContent();
            log.info("情感分析大模型原始返回: {}", content);

            return parseSentimentJson(content);
        } catch (ApiException e) {
            log.error("情感分析 API 异常: {}", e.getMessage(), e);
            return "[情感分析失败, 调用大模型异常: " + e.getMessage() + "]";
        } catch (NoApiKeyException e) {
            log.error("情感分析 API Key 未配置: {}", e.getMessage());
            return "[API Key 未配置, 请设置环境变量 DASHSCOPE_API_KEY]";
        } catch (Exception e) {
            log.error("情感分析异常: {}", e.getMessage(), e);
            return "[情感分析失败: " + e.getMessage() + "]";
        }
    }

    /** 情感分析专用系统提示词：强制输出严格 JSON，便于程序解析 */
    private static final String SENTIMENT_SYSTEM_PROMPT =
            "你是专业的中文情感分析引擎。对用户输入的文本进行精细情感分析，" +
            "只输出一个 JSON 对象，禁止输出任何其他文字、解释或 markdown 代码块标记。\n" +
            "JSON 字段如下：\n" +
            "- overall：整体情感类别，取值仅限 \"积极\"、\"中性\"、\"消极\" 之一\n" +
            "- subEmotion：情绪细分，例如 开心、夸赞、感谢、生气、抱怨、失望、担忧、嘲讽、平淡 等\n" +
            "- confidence：情绪置信度，0-100 的整数，越确定数值越高\n" +
            "- keywords：数组，列出文本中支撑该判断的关键词\n" +
            "- reason：简短理由（一句话），说明这些关键词如何支撑该判断\n" +
            "示例输入：你太笨了！\n" +
            "示例输出：{\"overall\":\"消极\",\"subEmotion\":\"生气\",\"confidence\":90,\"keywords\":[\"笨\"],\"reason\":\"“笨”带有侮辱性质，表达对对方智力的负面评价\"}";

    /**
     * 解析大模型返回的情感分析 JSON，重新格式化为稳定字符串。
     * 容错：剥离可能存在的 ```json 代码块标记，提取首个 { ... } 对象。
     */
    private String parseSentimentJson(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "[大模型未返回有效情感分析结果]";
        }
        String raw = content.trim();
        // 兼容模型偶尔包裹 ```json ... ``` 的情况
        if (raw.startsWith("```")) {
            int firstBrace = raw.indexOf('{');
            int lastBrace = raw.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                raw = raw.substring(firstBrace, lastBrace + 1);
            }
        }

        try {
            JsonObject obj = JsonUtils.parseString(raw).getAsJsonObject();
            String overall = obj.has("overall") && !obj.get("overall").isJsonNull()
                    ? obj.get("overall").getAsString() : "未知";
            String subEmotion = obj.has("subEmotion") && !obj.get("subEmotion").isJsonNull()
                    ? obj.get("subEmotion").getAsString() : "未知";
            int confidence = obj.has("confidence") && !obj.get("confidence").isJsonNull()
                    ? obj.get("confidence").getAsInt() : -1;
            String reason = obj.has("reason") && !obj.get("reason").isJsonNull()
                    ? obj.get("reason").getAsString() : "";
            // 关键词列表拼接到理由中
            String keywordsStr = "";
            if (obj.has("keywords") && obj.get("keywords").isJsonArray()) {
                List<String> kws = new ArrayList<>();
                obj.get("keywords").getAsJsonArray().forEach(e -> kws.add(e.getAsString()));
                keywordsStr = String.join("、", kws);
            }

            StringBuilder sb = new StringBuilder();
            sb.append("整体情感: ").append(overall)
              .append(" | 情绪细分: ").append(subEmotion)
              .append(" | 置信度: ").append(confidence >= 0 ? confidence + "%" : "未知");
            if (!keywordsStr.isEmpty()) {
                sb.append(" | 关键词: ").append(keywordsStr);
            }
            if (!reason.isEmpty()) {
                sb.append(" | 理由: ").append(reason);
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("情感分析 JSON 解析失败, 原始返回: {}", content, e);
            // 解析失败时直接返回大模型原文，保证链路不中断
            return content;
        }
    }

    // ======================== 工具注册（使用 ToolFunction，禁止手写 JSON-Schema） ========================

    /**
     * 构建工具列表，使用 FunctionDefinition.builder() + ToolFunction.builder() 注册工具
     * 参数 schema 通过 Gson JsonObject 编程式构建，不手写 JSON-Schema 字符串
     *
     * @return 已注册的 ToolFunction 列表
     */
    private List<ToolBase> buildTools() {
        // ---- 工具 1: getZoneTime ----
        JsonObject zoneTimeParams = new JsonObject();
        zoneTimeParams.addProperty("type", "object");

        JsonObject timezoneProp = new JsonObject();
        timezoneProp.addProperty("type", "string");
        timezoneProp.addProperty("description",
                "时区标识符，如 Asia/Shanghai、America/New_York、Europe/London 等。不传则返回本机当前时间。");

        JsonObject zoneTimeProperties = new JsonObject();
        zoneTimeProperties.add("timezone", timezoneProp);
        zoneTimeParams.add("properties", zoneTimeProperties);
        // timezone 为可选参数，不放入 required

        FunctionDefinition zoneTimeFunc = FunctionDefinition.builder()
                .name("getZoneTime")
                .description("获取指定时区的当前日期、时间和星期。不传时区参数则返回本机当前时间。")
                .parameters(zoneTimeParams)
                .build();

        // ---- 工具 2: dateToWeekday ----
        JsonObject dateToWeekdayParams = new JsonObject();
        dateToWeekdayParams.addProperty("type", "object");

        JsonObject dateProp = new JsonObject();
        dateProp.addProperty("type", "string");
        dateProp.addProperty("description", "日期字符串，格式为 yyyy-MM-dd，如 2026-10-01");

        JsonObject dateToWeekdayProperties = new JsonObject();
        dateToWeekdayProperties.add("date", dateProp);
        dateToWeekdayParams.add("properties", dateToWeekdayProperties);

        JsonArray requiredFields = new JsonArray();
        requiredFields.add("date");
        dateToWeekdayParams.add("required", requiredFields);

        FunctionDefinition dateToWeekdayFunc = FunctionDefinition.builder()
                .name("dateToWeekday")
                .description("根据公历日期字符串（yyyy-MM-dd格式）查询该日期是星期几。")
                .parameters(dateToWeekdayParams)
                .build();

        // ---- 工具 3: getSentimentAnalysis ----
        JsonObject sentimentParams = new JsonObject();
        sentimentParams.addProperty("type", "object");

        JsonObject textProp = new JsonObject();
        textProp.addProperty("type", "string");
        textProp.addProperty("description", "待进行情感分析的文本内容，可以是用户的一句话或一段话。");

        JsonObject sentimentProperties = new JsonObject();
        sentimentProperties.add("text", textProp);
        sentimentParams.add("properties", sentimentProperties);

        JsonArray sentimentRequired = new JsonArray();
        sentimentRequired.add("text");
        sentimentParams.add("required", sentimentRequired);

        FunctionDefinition sentimentFunc = FunctionDefinition.builder()
                .name("getSentimentAnalysis")
                .description("对用户输入的文本进行情感分析，返回整体情感类别（积极/中性/消极）、情绪细分（如开心、生气、抱怨、失望、平淡、夸赞等）、情绪置信度（0-100）以及简短理由。调用大模型进行语义级分析，能识别隐含情绪和未登录词。")
                .parameters(sentimentParams)
                .build();

        // 包装为 ToolFunction 列表（ToolFunction extends ToolBase）
        List<ToolBase> tools = new ArrayList<>();
        tools.add(ToolFunction.builder().function(zoneTimeFunc).build());
        tools.add(ToolFunction.builder().function(dateToWeekdayFunc).build());
        tools.add(ToolFunction.builder().function(sentimentFunc).build());
        return tools;
    }

    // ======================== Function-Calling 主链路 ========================

    /**
     * 带 Function Calling 的对话入口
     *
     * 完整链路：
     * 1. 将用户消息 + 工具清单发送给大模型（第一次调用）
     * 2. 大模型判断是否需要调用工具：
     *    - 不需要 → 直接返回自然语言回复
     *    - 需要   → 返回 tool_calls（工具名 + 参数）
     * 3. 在本地执行对应的 Java 工具方法，获取结果
     * 4. 将工具结果回传给大模型（第二次调用）
     * 5. 大模型综合工具结果，组装自然语言回复
     *
     * @param userMessage 用户输入的文字消息
     * @return 大模型最终回复文本
     */
    public String chatWithTools(String userMessage) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            log.warn("DashScope API Key 未配置, Function Calling 不可用");
            return "[API Key 未配置, 请设置环境变量 DASHSCOPE_API_KEY]";
        }

        Generation gen = new Generation();

        // 构建消息列表：system + user
        List<Message> messages = new ArrayList<>();
        messages.add(Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build());
        messages.add(Message.builder().role(Role.USER.getValue()).content(userMessage).build());

        try {
            // 第一次调用：发送用户问题 + 工具清单
            GenerationParam param = GenerationParam.builder()
                    .apiKey(apiKey)
                    .model(textModel)
                    .messages(messages)
                    .resultFormat(ResultFormat.MESSAGE)
                    .tools(buildTools())
                    .build();

            log.info("Function Calling 第一次调用: userMessage={}", userMessage);
            GenerationResult result = gen.call(param);

            Choice choice = result.getOutput().getChoices().get(0);
            Message assistantMsg = choice.getMessage();
            messages.add(assistantMsg);

            // 检查大模型是否要求调用工具
            if (assistantMsg.getToolCalls() != null && !assistantMsg.getToolCalls().isEmpty()) {
                // 遍历所有工具调用，逐个执行本地 Java 方法
                for (ToolCallBase toolCall : assistantMsg.getToolCalls()) {
                    if (!"function".equals(toolCall.getType())) {
                        continue;
                    }
                    ToolCallFunction funcCall = (ToolCallFunction) toolCall;
                    String funcName = funcCall.getFunction().getName();
                    String funcArgs = funcCall.getFunction().getArguments();

                    log.info("大模型请求调用工具: name={}, args={}", funcName, funcArgs);

                    // 执行本地 Java 工具方法
                    String toolResult = executeTool(funcName, funcArgs);
                    log.info("工具执行结果: {}", toolResult);

                    // 构造工具返回消息，回传给大模型
                    Message toolMsg = Message.builder()
                            .role("tool")
                            .content(toolResult)
                            .toolCallId(toolCall.getId())
                            .build();
                    messages.add(toolMsg);
                }

                // 第二次调用：将工具结果回传给大模型，获取最终自然语言回复
                param = GenerationParam.builder()
                        .apiKey(apiKey)
                        .model(textModel)
                        .messages(messages)
                        .resultFormat(ResultFormat.MESSAGE)
                        .tools(buildTools())
                        .build();

                log.info("Function Calling 第二次调用: 回传工具结果");
                result = gen.call(param);

                String finalContent = result.getOutput().getChoices().get(0).getMessage().getContent();
                log.info("Function Calling 最终回复: {}", finalContent);
                return finalContent != null ? finalContent : "[大模型未返回有效内容]";
            } else {
                // 大模型未调用工具，直接返回回复内容
                String content = assistantMsg.getContent();
                log.info("大模型直接回复（无需调用工具）: {}", content);
                return content != null ? content : "[大模型未返回有效内容]";
            }

        } catch (ApiException e) {
            log.error("Function Calling API 异常: {}", e.getMessage(), e);
            return "[调用大模型失败: " + e.getMessage() + "]";
        } catch (NoApiKeyException e) {
            log.error("API Key 未配置: {}", e.getMessage());
            return "[API Key 未配置, 请设置环境变量 DASHSCOPE_API_KEY]";
        } catch (InputRequiredException e) {
            log.error("输入参数缺失: {}", e.getMessage());
            return "[输入参数缺失: " + e.getMessage() + "]";
        } catch (Exception e) {
            log.error("Function Calling 异常: {}", e.getMessage(), e);
            return "[Function Calling 失败: " + e.getMessage() + "]";
        }
    }

    /**
     * 根据工具名称执行对应的本地 Java 方法
     *
     * @param name      工具名称（getZoneTime / dateToWeekday / getSentimentAnalysis）
     * @param arguments 模型传入的 JSON 格式参数字符串
     * @return 工具执行结果字符串
     */
    private String executeTool(String name, String arguments) {
        try {
            JsonObject args = JsonUtils.parseString(arguments != null ? arguments : "{}").getAsJsonObject();

            switch (name) {
                case "getZoneTime":
                    String timezone = (args.has("timezone") && !args.get("timezone").isJsonNull())
                            ? args.get("timezone").getAsString() : null;
                    return getZoneTime(timezone);

                case "dateToWeekday":
                    String date = (args.has("date") && !args.get("date").isJsonNull())
                            ? args.get("date").getAsString() : null;
                    return dateToWeekday(date);

                case "getSentimentAnalysis":
                    String saText = (args.has("text") && !args.get("text").isJsonNull())
                            ? args.get("text").getAsString() : null;
                    return getSentimentAnalysis(saText);

                default:
                    return "未知工具: " + name;
            }
        } catch (Exception e) {
            log.error("执行工具失败: name={}, args={}, error={}", name, arguments, e.getMessage(), e);
            return "工具执行失败: " + e.getMessage();
        }
    }

    // ======================== 测试入口 ========================
    // 密钥从环境变量 DASHSCOPE_API_KEY 读取，禁止硬编码
    // 测试命令：mvn compile exec:java -Dexec.mainClass="com.example.wechatbot.service.FunctionCallService"
    //
    // 【演示提问】
    //   示例 1：现在几点          → 大模型调用 getZoneTime（无参数），返回本机时间
    //           纽约现在几点      → 大模型调用 getZoneTime（timezone=America/New_York），返回纽约时间
    //   示例 2：2026-10-01是星期几 → 大模型调用 dateToWeekday（date=2026-10-01），返回星期四
    //   示例 3：分析这句话的情感：这破产品太垃圾了 → 大模型调用 getSentimentAnalysis，返回消极/抱怨
    public static void main(String[] args) {
        // 从环境变量读取 API Key，禁止硬编码
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("【错误】未设置环境变量 DASHSCOPE_API_KEY，请先配置 DashScope API Key");
            System.out.println("配置方式：set DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx");
            return;
        }

        // 手动构造 FunctionCallService（非 Spring 环境），密钥从环境变量读取
        FunctionCallService service = new FunctionCallService();
        try {
            java.lang.reflect.Field f;
            f = FunctionCallService.class.getDeclaredField("apiKey"); f.setAccessible(true); f.set(service, apiKey);
            f = FunctionCallService.class.getDeclaredField("textModel"); f.setAccessible(true); f.set(service, "qwen-turbo");
        } catch (Exception e) {
            System.out.println("【错误】反射注入失败: " + e.getMessage());
            return;
        }

        System.out.println("====== Function Calling 测试 ======\n");

        // 示例 1：现在几点 → 大模型应调用 getZoneTime（无参数），返回本机当前时间
        System.out.println("--- 示例 1a: 现在几点 ---");
        System.out.println("用户: 现在几点");
        System.out.println("助手: " + service.chatWithTools("现在几点"));
        System.out.println();

        // 示例 1：纽约现在几点 → 大模型应调用 getZoneTime（timezone=America/New_York）
        System.out.println("--- 示例 1b: 纽约现在几点 ---");
        System.out.println("用户: 纽约现在几点");
        System.out.println("助手: " + service.chatWithTools("纽约现在几点"));
        System.out.println();

        // 示例 2：2026-10-01是星期几 → 大模型应调用 dateToWeekday（date=2026-10-01）
        System.out.println("--- 示例 2: 2026-10-01是星期几 ---");
        System.out.println("用户: 2026-10-01是星期几");
        System.out.println("助手: " + service.chatWithTools("2026-10-01是星期几"));
        System.out.println();

        // 示例 3a：情感分析（消极） → 大模型应调用 getSentimentAnalysis
        System.out.println("--- 示例 3a: 情感分析（消极）---");
        System.out.println("用户: 帮我分析这句话的情感：这个破产品真的太垃圾了，我非常失望");
        System.out.println("助手: " + service.chatWithTools("帮我分析这句话的情感：这个破产品真的太垃圾了，我非常失望"));
        System.out.println();

        // 示例 3b：情感分析（积极） → 大模型应调用 getSentimentAnalysis
        System.out.println("--- 示例 3b: 情感分析（积极）---");
        System.out.println("用户: 分析一下这段话的情绪：太棒了，服务非常出色，必须点赞！");
        System.out.println("助手: " + service.chatWithTools("分析一下这段话的情绪：太棒了，服务非常出色，必须点赞！"));
        System.out.println();

        // 附加测试：普通对话（不触发工具调用）
        System.out.println("--- 附加: 普通对话（无需调用工具）---");
        System.out.println("用户: 你好，介绍一下你自己");
        System.out.println("助手: " + service.chatWithTools("你好，介绍一下你自己"));
        System.out.println();

        System.out.println("====== 测试结束 ======");
    }
}
