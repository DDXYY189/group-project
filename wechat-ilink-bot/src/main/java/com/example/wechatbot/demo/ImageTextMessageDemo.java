package com.example.wechatbot.demo;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 图文组合消息报文组装示例（不改动原有业务代码）
 *
 * 【背景】
 * iLink 协议中 MessageItem 的 text_item 和 image_item 是两个独立字段，
 * ImageItem 本身没有 text/caption/description 等文本属性。
 * 因此不能通过单个 MessageItem 同时携带图片和文字。
 *
 * 【为什么不能直接发两条独立消息】
 * 1. 时序问题：分开发送两条消息，Agent 端可能先收到文字再收到图片，
 *    或先收到图片再收到文字，无法保证两者被同一次推理处理。
 * 2. 上下文割裂：两条消息被当作独立请求，Agent 无法将图片和文字关联起来。
 *    比如用户发"这张图里有什么动物？"然后发一张猫的照片，
 *    分两条消息时 Agent 收到文字时还没看到图片，无法回答。
 * 3. 竞态条件：多用户场景下，两条消息之间可能插入其他用户的消息，
 *    导致图片和文字被错误关联到其他上下文。
 *
 * 【解决方案】
 * 将图片资源引用和用户文字描述合并封装到同一个请求报文内，
 * 通过自定义的 combined_message 结构，让 Agent 在一次接收中同时拿到
 * 图片资源标识和文字描述，实现联合推理。
 */
public class ImageTextMessageDemo {

    /**
     * 组装图文合并报文
     *
     * 报文结构说明：
     * {
     *   "combined_message": {
     *     "text": "用户文字描述",              // 用户输入的文字内容
     *     "image_ref": {                      // 图片资源引用（不传二进制，只传标识）
     *       "image_id": "img_xxx",             // 图片唯一标识（用于后续下载引用）
     *       "cdn_url": "https://cdn.xxx/img",  // 图片 CDN 下载地址
     *       "aes_key": "aes_xxx"              // 图片解密密钥（iLink 协议字段）
     *     }
     *   },
     *   "context": {                           // 附带的上下文信息
     *     "from_user_id": "wxid_xxx",         // 发送者
     *     "timestamp": 1787038800000,         // 时间戳
     *     "msg_type": "image_text"            // 标记为图文组合消息
     *   }
     * }
     *
     * 【关键字段说明】
     * - text 字段：放置用户文字描述，是 Agent 推理的文本输入
     * - image_ref 字段：放置图片资源引用（image_id / cdn_url / aes_key），
     *   Agent 收到后可通过 cdn_url 下载图片，再与 text 一起做多模态推理
     * - msg_type: "image_text"：标记这是一条图文组合消息，
     *   Agent 端据此走联合推理逻辑，而非单独处理文字或图片
     *
     * @param text        用户文字描述
     * @param imageId     图片唯一标识
     * @param cdnUrl      图片 CDN 下载地址
     * @param aesKey      图片解密密钥
     * @param fromUserId  发送者 ID
     * @return 组装好的 JSON 报文字符串
     */
    public static String buildImageTextMessage(String text, String imageId,
                                                String cdnUrl, String aesKey,
                                                String fromUserId) {

        // 构建 image_ref：图片资源引用字段
        // 不传图片二进制数据，只传引用标识，Agent 端按需下载
        Map<String, Object> imageRef = new HashMap<>();
        imageRef.put("image_id", imageId);       // 图片唯一标识
        imageRef.put("cdn_url", cdnUrl);          // CDN 下载地址
        imageRef.put("aes_key", aesKey);          // 解密密钥

        // 构建 combined_message：图文合并核心结构
        // text 放用户文字描述，image_ref 放图片资源引用
        Map<String, Object> combinedMessage = new HashMap<>();
        combinedMessage.put("text", text);         // 用户文字描述放在这里
        combinedMessage.put("image_ref", imageRef); // 图片资源引用放在这里

        // 构建 context：上下文信息
        Map<String, Object> context = new HashMap<>();
        context.put("from_user_id", fromUserId);
        context.put("timestamp", System.currentTimeMillis());
        // msg_type 标记为 image_text，Agent 端据此走联合推理
        context.put("msg_type", "image_text");

        // 组装完整报文
        Map<String, Object> payload = new HashMap<>();
        payload.put("combined_message", combinedMessage);
        payload.put("context", context);

        return JSON.toJSONString(payload);
    }

    /**
     * 模拟 Agent 端接收图文合并报文后的处理逻辑
     *
     * Agent 收到报文后的处理流程：
     * 1. 解析 msg_type，识别为 "image_text" 图文组合消息
     * 2. 从 combined_message.text 提取用户文字描述
     * 3. 从 combined_message.image_ref 提取图片资源引用
     * 4. 通过 cdn_url 下载图片（此处只做模拟，不做实际 IO）
     * 5. 将图片和文字一起传给多模态大模型做联合推理
     *
     * @param jsonPayload 收到的 JSON 报文
     */
    public static void agentHandleImageText(String jsonPayload) {
        JSONObject payload = JSON.parseObject(jsonPayload);

        // 第一步：识别消息类型
        String msgType = payload.getJSONObject("context").getString("msg_type");
        if (!"image_text".equals(msgType)) {
            System.out.println("[Agent] 非图文组合消息, 走普通处理逻辑");
            return;
        }

        // 第二步：提取文字描述
        JSONObject combined = payload.getJSONObject("combined_message");
        String text = combined.getString("text");

        // 第三步：提取图片资源引用
        JSONObject imageRef = combined.getJSONObject("image_ref");
        String imageId = imageRef.getString("image_id");
        String cdnUrl = imageRef.getString("cdn_url");
        String aesKey = imageRef.getString("aes_key");

        // 第四步：打印解析结果（实际场景中这里会下载图片并做多模态推理）
        System.out.println("[Agent] 收到图文组合消息:");
        System.out.println("  消息类型: " + msgType);
        System.out.println("  文字描述: " + text);
        System.out.println("  图片ID:  " + imageId);
        System.out.println("  CDN地址: " + cdnUrl);
        System.out.println("  解密密钥: " + aesKey);
        System.out.println("  → 下一步: 下载图片 + 文字一起送入多模态大模型推理");
    }

    /**
     * 测试入口：模拟组装两条图文合并报文并交给 Agent 处理
     */
    public static void main(String[] args) {
        System.out.println("====== 图文组合消息报文组装示例 ======\n");

        // 模拟场景1：用户发图片并附带提问
        System.out.println("--- 场景1: 用户发猫咪图片并提问 ---");
        String payload1 = buildImageTextMessage(
                "这张图片里的动物是什么品种？",      // 用户文字描述
                "img_20260818_001",                 // 图片唯一标识
                "https://cdn.weixin.ilink.com/msg/img_001.jpg", // CDN 下载地址
                "aes_key_001",                      // 解密密钥
                "wxid_abc123"                       // 发送者
        );
        System.out.println("组装报文:");
        System.out.println(formatJson(payload1));
        System.out.println();
        agentHandleImageText(payload1);

        System.out.println("\n" + "=".repeat(50) + "\n");

        // 模拟场景2：用户发风景图并要求描述
        System.out.println("--- 场景2: 用户发风景图并要求描述 ---");
        String payload2 = buildImageTextMessage(
                "帮我描述一下这个风景",               // 用户文字描述
                "img_20260818_002",                  // 图片唯一标识
                "https://cdn.weixin.ilink.com/msg/img_002.jpg", // CDN 下载地址
                "aes_key_002",                      // 解密密钥
                "wxid_xyz789"                        // 发送者
        );
        System.out.println("组装报文:");
        System.out.println(formatJson(payload2));
        System.out.println();
        agentHandleImageText(payload2);

        System.out.println("\n====== 示例结束 ======");
    }

    /**
     * 简单 JSON 格式化输出（美化可读）
     */
    private static String formatJson(String json) {
        JSONObject obj = JSON.parseObject(json);
        return JSON.toJSONString(obj, true);
    }
}
