package com.example.wechatbot;

import com.example.wechatbot.service.IntentService;
import com.example.wechatbot.service.IntentService.IntentResult;
import org.springframework.web.client.RestTemplate;

/**
 * 意图识别模块测试入口（独立运行，不需要启动 Spring 容器）
 * 运行前请设置环境变量：DASHSCOPE_API_KEY
 * 覆盖 text / voice / image（image_gen / image_edit）测试案例
 * 密钥从环境变量读取，禁止硬编码
 */
public class IntentMain {

    public static void main(String[] args) {
        // 从环境变量读取 API Key，禁止硬编码
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        if (apiKey == null || apiKey.trim().isEmpty()) {
            System.err.println("请先设置环境变量 DASHSCOPE_API_KEY");
            System.err.println("Windows:  set DASHSCOPE_API_KEY=你的key");
            System.err.println("Linux:    export DASHSCOPE_API_KEY=你的key");
            return;
        }

        // 手动构建 IntentService（不走 Spring 注入）
        RestTemplate restTemplate = new RestTemplate();
        IntentService intentService = new IntentService(restTemplate);
        // 通过反射注入 apiKey 和默认配置
        injectField(intentService, "apiKey", apiKey);
        injectField(intentService, "intentModel", "qwen-turbo");
        injectField(intentService, "baseUrl", "https://dashscope.aliyuncs.com/api/v1");

        System.out.println("====== 意图识别测试 ======\n");

        // 案例1：帮我画一只猫 → image_gen（全新画图，没有上传图片）
        test(intentService, "帮我画一只猫", "image", "image_gen");

        // 案例2：把这张图背景改成黑色 → image_edit（编辑已上传图片）
        test(intentService, "把这张图背景改成黑色", "image", "image_edit");

        // 案例3：text 意图 - 普通聊天
        test(intentService, "你好，今天天气怎么样？", "text", null);

        // 案例4：voice 意图 - 语音播报
        test(intentService, "请用语音读出来", "voice", null);

        // 额外测试案例
        test(intentService, "1+1等于几", "text", null);
        test(intentService, "语音播报一下今天的新闻", "voice", null);
        test(intentService, "生成一张日落风景画", "image", "image_gen");

        System.out.println("====== 测试结束 ======");
    }

    /**
     * 执行单个测试案例并打印结果
     * @param service    意图识别服务
     * @param input      用户输入
     * @param expectIntent   期望意图
     * @param expectSubIntent 期望子意图
     */
    private static void test(IntentService service, String input,
                             String expectIntent, String expectSubIntent) {
        System.out.println("用户输入: " + input);
        IntentResult result = service.recognize(input);
        System.out.println("  → intent=" + result.getIntent()
                + ", subIntent=" + result.getSubIntent());
        System.out.println("  期望: intent=" + expectIntent
                + ", subIntent=" + expectSubIntent);
        // 标注是否匹配
        boolean intentOk = expectIntent.equals(result.getIntent());
        boolean subOk = (expectSubIntent == null) ? result.getSubIntent() == null
                : expectSubIntent.equals(result.getSubIntent());
        System.out.println("  结果: " + (intentOk && subOk ? "✓ 匹配" : "✗ 不匹配"));
        if (result.getPrompt() != null) {
            System.out.println("  画图提示词: " + result.getPrompt());
        }
        System.out.println();
    }

    /** 通过反射注入私有字段 */
    private static void injectField(Object target, String fieldName, String value) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            System.err.println("注入字段失败: " + fieldName + " - " + e.getMessage());
        }
    }
}
