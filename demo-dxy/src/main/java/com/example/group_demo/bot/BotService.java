package com.example.group_demo.bot;

import com.example.group_demo.llm.LlmService;
import com.example.group_demo.image.ImageService;
import com.example.group_demo.intent.Intent;
import com.example.group_demo.intent.ImageTextMerger;
import com.example.group_demo.intent.IntentService;
import com.example.group_demo.rag.RagService;
import com.example.group_demo.skill.Skill;
import com.example.group_demo.skill.SkillRegistry;
import com.example.group_demo.tool.ToolRegistry;
import com.example.group_demo.voice.VoiceService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.TextItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class BotService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BotService.class);

    private final ILinkClient client;
    private final LlmService llmService;
    private final VoiceService voiceService;
    private final IntentService intentService;
    private final ImageService imageService;
    private final ToolRegistry toolRegistry;
    private final ImageTextMerger imageTextMerger;
    private final MessageDispatcher messageDispatcher;
    private final SkillRegistry skillRegistry;
    private final RagService ragService;

    private volatile byte[] qrPng;
    private volatile String loginError;
    private volatile LoginContext loginContext;

    public BotService(@Lazy ILinkClient client, LlmService llmService, VoiceService voiceService,
                      IntentService intentService, ImageService imageService,
                      ToolRegistry toolRegistry, ImageTextMerger imageTextMerger,
                      MessageDispatcher messageDispatcher, SkillRegistry skillRegistry,
                      RagService ragService) {
        this.client = client;
        this.llmService = llmService;
        this.voiceService = voiceService;
        this.intentService = intentService;
        this.imageService = imageService;
        this.toolRegistry = toolRegistry;
        this.imageTextMerger = imageTextMerger;
        this.messageDispatcher = messageDispatcher;
        this.skillRegistry = skillRegistry;
        this.ragService = ragService;
    }

    @Override
    public void run(ApplicationArguments args) {
        startLogin();
    }

    public void startLogin() {
        this.qrPng = null;
        this.loginError = null;
        try {
            String qrContent = client.executeLogin();
            this.qrPng = buildQrPng(qrContent);
            log.info("二维码已就绪，访问 http://localhost:8080 扫码登录");
        } catch (Exception e) {
            this.loginError = e.getMessage();
            log.error("获取登录二维码失败", e);
        }
    }

    public void onLoginSuccess(LoginContext context) {
        this.loginContext = context;
        log.info("登录成功 botId={} userId={}", context.getBotId(), context.getUserId());
    }

    public void onLoginFailure(Throwable throwable) {
        this.loginError = throwable.getMessage();
        log.error("登录失败: {}", throwable.getMessage());
    }

    public void onMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage message : messages) {
            if (message == null) {
                continue;
            }
            String fromUserId = message.getFrom_user_id();
            if (fromUserId == null) {
                log.info("收到无发送人的消息 messageId={}", message.getMessage_id());
                continue;
            }
            messageDispatcher.submit(fromUserId, () -> handleMessage(message));
        }
    }

    private void handleMessage(WeixinMessage message) {
        String fromUserId = message.getFrom_user_id();
        if (isSelfMessage(message)) {
            log.info("跳过机器人自身消息 from={}", fromUserId);
            return;
        }
        List<MessageItem> items = message.getItem_list();
        if (items == null || items.isEmpty()) {
            log.info("收到无内容的消息 from={} messageId={}", fromUserId, message.getMessage_id());
            return;
        }
        log.info("收到消息 from={} itemCount={} messageId={}",
            fromUserId, items.size(), message.getMessage_id());
        List<String> texts = new ArrayList<>();
        MessageItem imageItem = null;
        MessageItem voiceItem = null;
        for (MessageItem item : items) {
            log.info("消息项 from={} itemType={}", fromUserId, item.getType());
            TextItem textItem = item.getText_item();
            if (textItem != null && textItem.getText() != null) {
                texts.add(textItem.getText());
            }
            if (item.getImage_item() != null && imageItem == null) {
                imageItem = item;
            }
            if (item.getVoice_item() != null && voiceItem == null) {
                voiceItem = item;
            }
        }
        try {
            String combinedText = String.join(" ", texts).trim();
            if (imageItem != null && !combinedText.isEmpty()) {
                handleImageWithText(fromUserId, imageItem, combinedText);
            } else if (imageItem != null) {
                handleUserImage(fromUserId, imageItem);
            } else if (voiceItem != null) {
                handleUserVoice(fromUserId, voiceItem);
            } else if (!combinedText.isEmpty()) {
                handleUserText(fromUserId, combinedText);
            }
        } catch (Exception e) {
            log.error("处理消息失败 fromUserId={}", fromUserId, e);
            safeSendText(fromUserId, "消息处理失败：" + e.getMessage());
        }
    }

    private boolean isSelfMessage(WeixinMessage message) {
        LoginContext context = loginContext;
        if (context == null) {
            return false;
        }
        String from = message.getFrom_user_id();
        return from != null && from.equals(context.getBotId());
    }

    private void handleUserText(String fromUserId, String userText) {
        if (userText.contains("文件测试")) {
            sendFileTest(fromUserId);
            return;
        }
        Optional<ImageTextMerger.Pending> merged = imageTextMerger.tryMergeText(fromUserId, userText);
        if (merged.isPresent()) {
            ImageTextMerger.Pending pending = merged.get();
            handleImageAndText(fromUserId, userText, pending.imageBytes(), pending.fileName());
            return;
        }
        if (looksLikeImageUnderstanding(userText)) {
            safeSendText(fromUserId, "好的，请把图片发给我，我来帮你识别。");
            return;
        }

        // ============================================================
        // 固定顺序消息路由：Skill → RAG → LLM 兜底闲聊
        // 测试说明：
        //   ①测试Skill分支：发送 "距离12月4日还有多久" 或 "倒计时 春节"（需含日期）
        //   ②测试RAG开启：  发送 "你们公司介绍" 或 "价格多少"（rag.enable-rag=true 时生效）
        //   ③测试RAG关闭：  修改配置 rag.enable-rag=false 后发送 "你们公司介绍"
        //   ④测试兜底闲聊：  发送 "你好" 或 "今天天气怎么样" 等普通对话
        // ============================================================

        // 第1步：Skill 关键词匹配（优先级最高）
        Skill matchedSkill = skillRegistry.match(userText);
        if (matchedSkill != null) {
            log.info("【执行Skill】userId={} skill={} userText={}", fromUserId, matchedSkill.name(), userText);
            String result = matchedSkill.execute(fromUserId, userText);
            safeSendText(fromUserId, result);
            return;
        }

        // 第2步：RAG 关键词检索（开关开启且命中时增强 Prompt）
        if (ragService.isEnabled()) {
            List<String> ragFragments = ragService.search(userText);
            if (ragFragments != null && !ragFragments.isEmpty()) {
                log.info("【RAG增强prompt】userId={} hitCount={} userText={}",
                    fromUserId, ragFragments.size(), userText);
                String augmentedPrompt = ragService.buildAugmentedPrompt(ragFragments);
                String reply;
                if (llmService.isConfigured()) {
                    reply = replyToTextWithRag(fromUserId, userText, augmentedPrompt);
                } else {
                    log.warn("LLM 未配置，RAG 命中但无法调用大模型，直接返回知识库原文");
                    reply = "【RAG检索结果（LLM未配置，返回知识库原文）】\n" + String.join("\n---\n", ragFragments);
                }
                safeSendText(fromUserId, reply);
                return;
            }
        }

        // 第3步：LLM 兜底闲聊（正常对话回复）
        log.info("【LLM兜底闲聊】userId={} userText={}", fromUserId, userText);
        Intent intent = intentService.classify(userText);
        if (intent == null || intent.action() == null) {
            safeSendText(fromUserId, replyToText(fromUserId, userText));
            return;
        }
        switch (intent.action()) {
            case "voice" -> sendVoiceReply(fromUserId,
                replyToText(fromUserId, cleanInstruction(userText)));
            case "image" -> sendImageReply(fromUserId,
                intent.imagePrompt() != null ? intent.imagePrompt() : userText,
                intent.reply() != null ? intent.reply() : "图片生成完成");
            // 文本回复必须走带记忆的 chat，否则模型看不到历史上下文
            default -> safeSendText(fromUserId, replyToText(fromUserId, userText));
        }
    }

    private String cleanInstruction(String userText) {
        String text = userText == null ? "" : userText;
        String[] phrases = {
            "用语音回复我", "用语音回复", "语音回复我", "语音回复",
            "用语音回答我", "用语音回答", "语音回答我", "语音回答",
            "用语音来回复", "用语音来回答", "语音回"
        };
        for (String phrase : phrases) {
            text = text.replace(phrase, "");
        }
        text = text.replaceAll("[，。！？、,.!?\\s]+$", "").trim();
        return text.isBlank() ? userText : text;
    }

    private boolean looksLikeImageUnderstanding(String text) {
        boolean hasImageWord = text != null && text.contains("图");
        boolean hasAction = containsAny(text, "识别", "看看", "看一下", "描述", "解读", "帮我看看", "这是什么");
        return hasImageWord && hasAction;
    }

    private boolean containsAny(String text, String... keys) {
        if (text == null) {
            return false;
        }
        for (String key : keys) {
            if (text.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private void sendFileTest(String fromUserId) {
        try {
            byte[] fileBytes = "iLink 文件发送测试 OK".getBytes(StandardCharsets.UTF_8);
            client.sendFile(fromUserId, fileBytes, "test.txt", "文件发送测试");
            log.info("已发送文件测试 from={} size={}", fromUserId, fileBytes.length);
        } catch (Exception e) {
            log.warn("文件发送测试失败", e);
            safeSendText(fromUserId, "文件发送测试失败，请查看日志。");
        }
    }

    private void handleUserImage(String fromUserId, MessageItem item) {
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            Optional<ImageTextMerger.Pending> merged = imageTextMerger.tryMergeImage(
                fromUserId, imageBytes, "image.png");
            if (merged.isPresent()) {
                ImageTextMerger.Pending pending = merged.get();
                safeSendText(fromUserId,
                    llmService.chatWithImage(pending.text(), pending.imageBytes(), pending.fileName()));
            } else {
                safeSendText(fromUserId, "图片收到，你可以补充一句想要的处理要求。");
            }
        } catch (Exception e) {
            log.warn("图片消息处理失败", e);
            safeSendText(fromUserId, "图片解析失败，请稍后再试。");
        }
    }

    private void handleImageWithText(String fromUserId, MessageItem item, String text) {
        imageTextMerger.clear(fromUserId);
        try {
            byte[] imageBytes = client.downloadImageFromMessageItem(item);
            handleImageAndText(fromUserId, text, imageBytes, "image.png");
        } catch (Exception e) {
            log.warn("图文消息处理失败", e);
            safeSendText(fromUserId, "图文处理失败，请稍后再试。");
        }
    }

    private void handleImageAndText(String fromUserId, String text, byte[] imageBytes, String fileName) {
        if ("edit".equals(intentService.classifyImageText(text))) {
            try {
                byte[] edited = imageService.editImage(imageBytes, text);
                client.sendImage(fromUserId, edited, "edited.png", "已按你的要求处理完成");
                log.info("已发送编辑后图片 from={} size={}", fromUserId, edited.length);
                return;
            } catch (Exception e) {
                log.warn("图像编辑失败，回退图文理解", e);
            }
        }
        try {
            safeSendText(fromUserId, llmService.chatWithImage(text, imageBytes, fileName));
        } catch (Exception e) {
            log.warn("图文理解失败", e);
            safeSendText(fromUserId, "图文处理失败，请稍后再试。");
        }
    }

    private void handleUserVoice(String fromUserId, MessageItem item) {
        VoiceItem voiceItem = item.getVoice_item();
        try {
            byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
            log.info("收到语音 encodeType={} sampleRate={} playtime={} size={} header={}",
                voiceItem.getEncode_type(), voiceItem.getSample_rate(), voiceItem.getPlaytime(),
                voiceBytes.length, toHex(voiceBytes));

            String transcript = null;
            String serverText = voiceItem.getText();
            if (serverText != null && !serverText.isBlank()) {
                transcript = serverText.trim();
                log.info("使用服务端语音转写结果");
            } else {
                byte[] wavBytes;
                if (isSilk(voiceBytes)) {
                    wavBytes = voiceService.decodeSilkToWav(voiceBytes);
                } else {
                    wavBytes = voiceService.toWav(voiceBytes, detectAudioSuffix(voiceBytes));
                }
                transcript = voiceService.transcribe(wavBytes);
            }

            if (transcript == null || transcript.isBlank()) {
                safeSendText(fromUserId, "没有听清你说的内容，请再发一次语音或直接打字。");
                return;
            }
            log.info("语音转写结果 text={}", transcript);
            if (transcript.contains("语音回显") || transcript.contains("回显测试")) {
                echoVoice(fromUserId, item, voiceBytes);
                return;
            }
            handleUserText(fromUserId, transcript);
        } catch (Exception e) {
            log.warn("语音消息处理失败", e);
            safeSendText(fromUserId, "语音处理失败，请稍后再试。");
        }
    }

    private void echoVoice(String fromUserId, MessageItem item, byte[] voiceBytes) {
        try {
            VoiceItem voiceItem = item.getVoice_item();
            int sampleRate = voiceItem.getSample_rate() != null ? voiceItem.getSample_rate() : 16000;
            int playtime = voiceItem.getPlaytime() != null ? voiceItem.getPlaytime() : 3000;
            int encodeType = voiceItem.getEncode_type() != null ? voiceItem.getEncode_type() : 6;
            client.sendVoice(fromUserId, voiceBytes, "echo.silk", playtime, sampleRate,
                null, encodeType, 16, "语音回显测试");
            log.info("已回显语音 from={} encodeType={} sampleRate={} playtime={} size={}",
                fromUserId, encodeType, sampleRate, playtime, voiceBytes.length);
        } catch (Exception e) {
            log.warn("语音回显失败", e);
            safeSendText(fromUserId, "语音回显失败，请查看日志。");
        }
    }

    private void sendVoiceReply(String fromUserId, String replyText) {
        try {
            byte[] mp3Bytes = voiceService.synthesizeToMp3(replyText);
            client.sendFile(fromUserId, mp3Bytes, "reply.mp3", "语音回复");
            log.info("已发送语音文件回复 from={} size={}", fromUserId, mp3Bytes.length);
        } catch (Exception e) {
            log.warn("语音回复失败，回退文本", e);
            safeSendText(fromUserId, "语音回复失败，请稍后再试。");
        }
    }

    private void sendImageReply(String fromUserId, String imagePrompt, String replyText) {
        try {
            byte[] imageBytes = imageService.generateImage(imagePrompt);
            client.sendImage(fromUserId, imageBytes, "image.png", replyText);
            log.info("已发送图片回复 from={}", fromUserId);
        } catch (Exception e) {
            log.warn("图片生成失败，回退文本", e);
            safeSendText(fromUserId, "图片生成失败，请稍后再试。");
        }
    }

    private void safeSendText(String toUserId, String text) {
        try {
            client.sendTextWithTyping(toUserId, text, 800L);
            log.info("已回复 from={} reply={}", toUserId, text);
        } catch (Exception e) {
            log.error("回复消息失败 fromUserId={}", toUserId, e);
        }
    }

    private String detectAudioSuffix(byte[] bytes) {
        if (bytes.length >= 8 && new String(bytes, 0, 8, StandardCharsets.US_ASCII).startsWith("#!AMR")) {
            return ".amr";
        }
        if (bytes.length >= 9 && new String(bytes, 0, 9, StandardCharsets.US_ASCII).startsWith("#!SILK_V3")) {
            return ".silk";
        }
        if (bytes.length >= 4 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F') {
            return ".wav";
        }
        if (bytes.length >= 3 && bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3') {
            return ".mp3";
        }
        return ".silk";
    }

    private boolean isSilk(byte[] bytes) {
        String head = new String(bytes, 0, Math.min(12, bytes.length), StandardCharsets.US_ASCII);
        return head.contains("#!SILK_V3");
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        int count = Math.min(16, bytes.length);
        for (int i = 0; i < count; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private String replyToText(String fromUserId, String userText) {
        if (llmService.isConfigured()) {
            try {
                return llmService.chatWithTools(fromUserId, userText);
            } catch (Exception e) {
                log.warn("LLM 文本调用失败，回退为回显：{}", e.getMessage());
            }
        }
        return "收到：" + userText;
    }

    /**
     * 带 RAG 知识增强的文本回复。
     */
    private String replyToTextWithRag(String fromUserId, String userText, String systemAddon) {
        if (llmService.isConfigured()) {
            try {
                return llmService.chatWithTools(fromUserId, userText, systemAddon);
            } catch (Exception e) {
                log.warn("LLM RAG文本调用失败，回退为回显：{}", e.getMessage());
            }
        }
        return "收到：" + userText;
    }

    private byte[] buildQrPng(String qrContent) throws IOException, WriterException {
        String content = qrContent == null ? "" : qrContent.trim();
        if (content.startsWith("data:image")) {
            int commaIndex = content.indexOf(',');
            String base64 = commaIndex >= 0 ? content.substring(commaIndex + 1) : content;
            return Base64.getDecoder().decode(base64);
        }
        QRCodeWriter writer = new QRCodeWriter();
        BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, 400, 400);
        BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    public byte[] getQrPng() {
        return qrPng;
    }

    public boolean isLoggedIn() {
        return client.isLoggedIn();
    }

    public String getConnectionStatus() {
        return String.valueOf(client.getConnectionStatus());
    }

    public LoginContext getLoginContext() {
        return loginContext;
    }

    public boolean isLlmConfigured() {
        return llmService.isConfigured();
    }

    public String getLoginError() {
        return loginError;
    }

    public List<String> getToolNames() {
        return toolRegistry.names();
    }
}
