package com.example.wechatbot.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

/**
 * 意图识别服务
 * 用户输入一句话，通过大模型判断意图类型：text / voice / image
 * - text:  普通聊天问答，输出文字回复
 * - voice: 用户希望语音播报、朗读回复内容
 * - image: 用户要求画图、生成图片（细分为 image_gen / image_edit）
 *   - image_gen:  全新生成图片，用户没有上传图片，纯粹文字描述画图
 *   - image_edit: 编辑已上传的原图，用户上传图片 + 文字指令（改背景、换颜色、修改局部）
 */
@Service
public class IntentService {

    private static final Logger log = LoggerFactory.getLogger(IntentService.class);

    private final RestTemplate restTemplate;

    /** DashScope API Key，从配置/环境变量读取，严禁硬编码 */
    @Value("${llm.api-key}")
    private String apiKey;

    /** 意图识别使用的模型，默认 qwen-turbo（轻量快速） */
    @Value("${llm.intent-model:qwen-turbo}")
    private String intentModel;

    /** DashScope API 基础地址 */
    @Value("${llm.base-url:https://dashscope.aliyuncs.com/api/v1}")
    private String baseUrl;

    /** image 意图时的画图提示词模板 */
    private static final String IMAGE_PROMPT_TEMPLATE =
            "根据用户描述生成高质量图片，画面细节丰富，构图美观。用户描述：%s，只专注画面生成，不要多余文字输出";

    /**
     * 意图识别系统提示词：要求大模型只返回 JSON，不输出额外解释
     * image 意图细分为两个子类型：
     * - image_gen:  全新生成图片（用户没上传图片，纯文字描述画图）
     * - image_edit: 编辑已有图片（用户上传图片 + 修改指令，如改背景、换颜色、修改局部）
     * voice 意图细分为一个子类型：
     * - tts_speak: 用户要求语音播报、用MP3音频回复、朗读、读出来等
     */
    private static final String INTENT_SYSTEM_PROMPT =
            "你是一个意图分类器。根据用户输入判断意图类型，只返回JSON，格式 {\"intent\":\"text|voice|image\",\"subIntent\":null或\"image_gen\"或\"image_edit\"或\"tts_speak\"}。" +
            "分类规则：" +
            "1. text: 普通聊天、问答、闲聊、知识查询等，subIntent为null；" +
            "2. voice: 用户希望语音播报、朗读、用语音回复、用MP3音频回复、读出来等，" +
            "   包括但不限于：用MP3音频文件的形式发消息、用音频给我发、用语音说、用MP3给我、语音播报、读出来、朗读、用声音回复、发一段语音、用音频文件回复。" +
            "   subIntent为tts_speak；" +
            "3. image: 用户要求画图、生成图片、画一张图、生成一幅画等，需要进一步判断subIntent：" +
            "   - image_gen: 用户没有上传图片，纯粹用文字描述要画什么（如：帮我画一只猫、画一个夕阳）；" +
            "   - image_edit: 用户上传了图片并要求修改（如：把背景改成黑色、换一下颜色、去掉水印、修改局部）。" +
            "输出示例：" +
            "全新画图：{\"intent\":\"image\",\"subIntent\":\"image_gen\"}" +
            "编辑图片：{\"intent\":\"image\",\"subIntent\":\"image_edit\"}" +
            "语音播报：{\"intent\":\"voice\",\"subIntent\":\"tts_speak\"}" +
            "用MP3音频给我打招呼：{\"intent\":\"voice\",\"subIntent\":\"tts_speak\"}" +
            "用MP3音频文件的形式给我发一条消息：{\"intent\":\"voice\",\"subIntent\":\"tts_speak\"}" +
            "普通文字：{\"intent\":\"text\",\"subIntent\":null}" +
            "禁止输出任何额外解释文字，只返回JSON。";

    public IntentService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 检查 API Key 是否配置 */
    public boolean isReady() {
        return apiKey != null && !apiKey.trim().isEmpty();
    }

    /**
     * 识别用户意图
     * @param userInput 用户输入的文字
     * @return 意图结果对象，包含 intent 类型和相关数据
     */
    public IntentResult recognize(String userInput) {
        if (!isReady()) {
            log.warn("API Key 未配置, 无法调用意图识别");
            // API Key 未配置时默认走 text 意图
            return new IntentResult("text", null, null);
        }

        // 调用大模型做意图分类，使用 OpenAI 兼容接口
        String url = baseUrl.replace("/api/v1", "/compatible-mode/v1") + "/chat/completions";

        Map<String, Object> body = new HashMap<>();
        body.put("model", intentModel);
        // temperature 设为 0，保证分类结果稳定
        body.put("temperature", 0);
        body.put("messages", new Object[]{
                Map.of("role", "system", "content", INTENT_SYSTEM_PROMPT),
                Map.of("role", "user", "content", userInput)
        });

        try {
            JSONObject resp = postRequest(url, body);
            JSONArray choices = resp.getJSONArray("choices");
            if (choices != null && !choices.isEmpty()) {
                String content = choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
                log.info("意图识别原始返回: {}", content);

                // 解析大模型返回的 JSON，提取 intent 和 subIntent
                String intent = parseIntent(content);
                String subIntent = parseSubIntent(content);
                log.info("用户输入: [{}] → 意图: [{}], 子意图: [{}]", userInput, intent, subIntent);

                // 如果是 image 意图，附带画图提示词
                String prompt = null;
                if ("image".equals(intent)) {
                    prompt = String.format(IMAGE_PROMPT_TEMPLATE, userInput);
                }

                return new IntentResult(intent, subIntent, prompt);
            }
            // 大模型未返回有效内容，默认 text
            return new IntentResult("text", null, null);
        } catch (Exception e) {
            log.error("意图识别调用失败: {}", e.getMessage(), e);
            // 异常时降级为 text 意图
            return new IntentResult("text", null, null);
        }
    }

    /**
     * 从大模型返回内容中解析 intent 字段
     * 大模型可能返回纯 JSON 或带 markdown 代码块的 JSON，都需兼容
     */
    private String parseIntent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "text";
        }

        String trimmed = content.trim();

        // 去除可能的 markdown 代码块包裹
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }

        try {
            JSONObject json = JSON.parseObject(trimmed);
            String intent = json.getString("intent");
            if (intent != null) {
                intent = intent.trim().toLowerCase();
                // 只允许三种合法意图
                if ("text".equals(intent) || "voice".equals(intent) || "image".equals(intent)) {
                    return intent;
                }
            }
        } catch (Exception e) {
            log.warn("意图 JSON 解析失败, 原始内容: {}", content);
        }

        // 解析失败默认 text
        return "text";
    }

    /**
     * 从大模型返回内容中解析 subIntent 字段
     * image 意图子类型：image_gen（全新生成）/ image_edit（编辑原图）
     * voice 意图子类型：tts_speak（语音播报/MP3音频回复）
     * 其他意图返回 null
     */
    private String parseSubIntent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }

        String trimmed = content.trim();

        // 去除可能的 markdown 代码块包裹
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?\\s*", "").replaceAll("\\s*```$", "").trim();
        }

        try {
            JSONObject json = JSON.parseObject(trimmed);
            String subIntent = json.getString("subIntent");
            if (subIntent != null) {
                subIntent = subIntent.trim().toLowerCase();
                // 允许的合法子意图：image_gen / image_edit / tts_speak
                if ("image_gen".equals(subIntent) || "image_edit".equals(subIntent) || "tts_speak".equals(subIntent)) {
                    return subIntent;
                }
            }
        } catch (Exception e) {
            log.warn("子意图 JSON 解析失败, 原始内容: {}", content);
        }

        return null;
    }

    /** 发送 HTTP POST 请求并返回 JSON 响应 */
    private JSONObject postRequest(String url, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        HttpEntity<String> entity = new HttpEntity<>(JSON.toJSONString(body), headers);
        ResponseEntity<String> resp = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);
        return JSON.parseObject(resp.getBody());
    }

    /**
     * 意图识别结果
     * intent:    text / voice / image
     * subIntent: 仅 image 意图有子类型：image_gen（全新生成）/ image_edit（编辑原图），其他意图为 null
     * prompt:    仅 image 意图时携带画图提示词，其他意图为 null
     */
    public static class IntentResult {
        private final String intent;
        private final String subIntent;
        private final String prompt;

        public IntentResult(String intent, String subIntent, String prompt) {
            this.intent = intent;
            this.subIntent = subIntent;
            this.prompt = prompt;
        }

        public String getIntent() {
            return intent;
        }

        public String getSubIntent() {
            return subIntent;
        }

        public String getPrompt() {
            return prompt;
        }

        @Override
        public String toString() {
            return "IntentResult{intent='" + intent + "', subIntent='" + subIntent + "', prompt='" + prompt + "'}";
        }
    }

    // ===================== 测试入口 =====================
    // 密钥从环境变量 DASHSCOPE_API_KEY 读取，禁止硬编码
    // 测试命令：mvn compile exec:java -Dexec.mainClass="com.example.wechatbot.service.IntentService"
    public static void main(String[] args) {
        // 手动构造 IntentService（非 Spring 环境），密钥从环境变量读取
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.out.println("【错误】未设置环境变量 DASHSCOPE_API_KEY，请先配置 DashScope API Key");
            System.out.println("配置方式：set DASHSCOPE_API_KEY=sk-xxxxxxxxxxxxxxxx");
            return;
        }

        // 通过反射注入 apiKey（绕过 Spring 注入）
        IntentService service = new IntentService(new org.springframework.web.client.RestTemplate());
        try {
            java.lang.reflect.Field apiKeyField = IntentService.class.getDeclaredField("apiKey");
            apiKeyField.setAccessible(true);
            apiKeyField.set(service, apiKey);

            java.lang.reflect.Field intentModelField = IntentService.class.getDeclaredField("intentModel");
            intentModelField.setAccessible(true);
            intentModelField.set(service, "qwen-turbo");

            java.lang.reflect.Field baseUrlField = IntentService.class.getDeclaredField("baseUrl");
            baseUrlField.setAccessible(true);
            baseUrlField.set(service, "https://dashscope.aliyuncs.com/api/v1");
        } catch (Exception e) {
            System.out.println("【错误】反射注入失败: " + e.getMessage());
            return;
        }

        // 案例1：帮我画一只猫 → image_gen（全新画图，没有上传图片）
        IntentResult r1 = service.recognize("帮我画一只猫");
        System.out.println("案例1: 帮我画一只猫");
        System.out.println("  → intent=" + r1.getIntent() + ", subIntent=" + r1.getSubIntent());
        System.out.println("  期望: intent=image, subIntent=image_gen");
        System.out.println();

        // 案例2：把这张图背景改成黑色 → image_edit（编辑已上传图片）
        IntentResult r2 = service.recognize("把这张图背景改成黑色");
        System.out.println("案例2: 把这张图背景改成黑色");
        System.out.println("  → intent=" + r2.getIntent() + ", subIntent=" + r2.getSubIntent());
        System.out.println("  期望: intent=image, subIntent=image_edit");
        System.out.println();

        // 案例3：用MP3音频给我打招呼 → voice / tts_speak（语音播报）
        IntentResult r3 = service.recognize("用MP3音频给我打招呼");
        System.out.println("案例3: 用MP3音频给我打招呼");
        System.out.println("  → intent=" + r3.getIntent() + ", subIntent=" + r3.getSubIntent());
        System.out.println("  期望: intent=voice, subIntent=tts_speak");
        System.out.println();

        System.out.println("测试完成。");
    }
}
