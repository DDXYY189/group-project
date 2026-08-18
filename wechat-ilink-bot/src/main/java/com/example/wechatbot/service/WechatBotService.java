package com.example.wechatbot.service;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnDisconnectListener;
import com.github.wechat.ilink.sdk.core.listener.OnHeartbeatListener;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class WechatBotService {

    private static final Logger log = LoggerFactory.getLogger(WechatBotService.class);

    @Autowired
    private LlmService llmService;

    @Autowired
    private IntentService intentService;

    @Value("${wechat.enabled:true}")
    private boolean enabled;

    @Value("${wechat.auto-start:true}")
    private boolean autoStart;

    @Value("${wechat.poll-interval:1000}")
    private long pollInterval;

    private ILinkClient client;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private String qrCodeContent;

    /**
     * 待处理图片缓存：key=用户ID, value=PendingMessage
     * 用户发图片后缓存图片并主动询问意图，无限等待用户回复文字描述后合并处理
     */
    private final Map<String, PendingMessage> pendingMessageMap = new ConcurrentHashMap<>();

    /**
     * 待处理消息：缓存图片内容
     */
    private static class PendingMessage {
        final String msgType;       // 消息类型：image
        final byte[] imageBytes;    // 图片字节

        PendingMessage(String msgType, byte[] imageBytes) {
            this.msgType = msgType;
            this.imageBytes = imageBytes;
        }
    }

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("微信 Bot 未启用 (wechat.enabled=false)");
            return;
        }
        if (!autoStart) {
            log.info("微信 Bot 未自动启动, 请访问 http://localhost:8080/ 手动启动");
            return;
        }
        new Thread(this::start, "wechat-bot-start").start();
    }

    @PreDestroy
    public void destroy() {
        stop();
    }

    public void start() {
        if (running.get()) {
            log.warn("Bot 已在运行中");
            return;
        }
        running.set(true);
        log.info("========== 微信 Bot 启动中 ==========");

        try {
            ILinkConfig config = ILinkConfig.builder()
                    .connectTimeoutMs(35000)
                    .readTimeoutMs(35000)
                    .writeTimeoutMs(35000)
                    .httpMaxRetries(3)
                    .retryBaseDelayMs(1000)
                    .retryMaxDelayMs(10000)
                    .heartbeatEnabled(true)
                    .heartbeatIntervalMs(30000)
                    .channelVersion("1.0.0")
                    .build();

            client = ILinkClient.builder()
                    .config(config)
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("登录成功! botId = {}", context.getBotId());
                            loggedIn.set(true);
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("登录失败: {}", throwable.getMessage(), throwable);
                            loggedIn.set(false);
                        }
                    })
                    .onMessage(new OnMessageListener() {
                        @Override
                        public void onMessages(List<WeixinMessage> messages) {
                            handleMessages(messages);
                        }
                    })
                    .onDisconnect(new OnDisconnectListener() {
                        @Override
                        public void onDisconnect(Throwable throwable) {
                            log.warn("连接断开: {}", throwable.getMessage());
                            loggedIn.set(false);
                        }

                        @Override
                        public void onReconnectStart(int attempt) {
                            log.info("正在尝试第 {} 次重连...", attempt);
                        }

                        @Override
                        public void onReconnectSuccess() {
                            log.info("重连成功, 恢复登录状态");
                            loggedIn.set(true);
                        }

                        @Override
                        public void onReconnectFailed(Throwable throwable) {
                            log.error("重连失败: {}", throwable.getMessage(), throwable);
                            loggedIn.set(false);
                        }
                    })
                    .onHeartbeat(new OnHeartbeatListener() {
                        @Override
                        public void onHeartbeatSuccess() {
                            log.debug("心跳正常");
                        }

                        @Override
                        public void onHeartbeatFailure(Throwable throwable) {
                            log.warn("心跳失败: {}", throwable.getMessage());
                        }
                    })
                    .build();

            qrCodeContent = client.executeLogin();
            log.info("二维码已生成, 请访问 http://localhost:8080/ 扫码登录");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成, botId = {}", context.getBotId());

            startMessageLoop();

        } catch (Exception e) {
            log.error("Bot 启动失败: {}", e.getMessage(), e);
            running.set(false);
        }
    }

    public void stop() {
        running.set(false);
        loggedIn.set(false);
        // 清空待处理图片缓存
        pendingMessageMap.clear();
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭客户端异常: {}", e.getMessage());
            }
        }
        log.info("微信 Bot 已停止");
    }

    public String getQrCodeContent() {
        return qrCodeContent;
    }

    public boolean isLoggedIn() {
        return loggedIn.get();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void startMessageLoop() {
        Thread pollThread = new Thread(() -> {
            log.info("消息轮询循环已启动");
            while (running.get() && loggedIn.get()) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null && !messages.isEmpty()) {
                        log.info("收到 {} 条消息", messages.size());
                    }
                } catch (Exception e) {
                    log.error("消息轮询异常: {}", e.getMessage(), e);
                    try {
                        Thread.sleep(pollInterval);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            log.info("消息轮询循环已停止");
        }, "wechat-message-poll");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void handleMessages(List<WeixinMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (WeixinMessage msg : messages) {
            try {
                handleSingleMessage(msg);
            } catch (Exception e) {
                log.error("处理消息异常: {}", e.getMessage(), e);
            }
        }
    }

    private void handleSingleMessage(WeixinMessage msg) throws java.io.IOException {
        String fromUserId = msg.getFrom_user_id();
        log.info("收到消息: fromUserId={}", fromUserId);

        if (msg.getItem_list() == null || msg.getItem_list().isEmpty()) {
            return;
        }

        for (MessageItem item : msg.getItem_list()) {

            if (item.getText_item() != null) {
                String text = item.getText_item().getText();
                log.info("文本消息: {}", text);

                // 检查是否有待处理的图片消息
                PendingMessage pending = pendingMessageMap.remove(fromUserId);
                if (pending != null && "image".equals(pending.msgType)) {
                    // 有待处理图片：合并图片+文字一起处理
                    log.info("检测到待处理图片, 与当前文字合并: userId={}", fromUserId);
                    handleMergedMessages(fromUserId, pending, text, null);
                    continue;
                }

                // 无待处理图片：直接处理文字消息（走意图识别）
                processTextMessage(fromUserId, text);
                continue;
            }

            if (item.getImage_item() != null) {
                log.info("收到图片消息, 下载并缓存");

                // 下载图片到内存
                byte[] imageBytes = client.downloadImageFromMessageItem(item);

                // 缓存图片（无限等待用户回复），主动询问用户想对图片做什么
                pendingMessageMap.put(fromUserId, new PendingMessage("image", imageBytes));
                log.info("图片已缓存, 等待用户回复意图描述: userId={}", fromUserId);
                try {
                    client.sendText(fromUserId, "收到图片！你想对这张图片进行什么操作？\n"
                            + "例如：改背景、生成新图片、识别图片内容等，请描述你的需求");
                } catch (Exception e) {
                    log.warn("发送图片询问消息失败: {}", e.getMessage());
                }
                continue;
            }

            if (item.getVoice_item() != null) {
                log.info("收到语音消息");

                // 语音识别：通过 iLink 协议直接提取语音气泡内的文字
                // 不调用任何外部音频识别 API，协议自带语音转文字能力
                String voiceText = item.getVoice_item().getText();

                if (voiceText == null || voiceText.isEmpty()) {
                    log.warn("语音消息未携带转写文字");
                    client.sendText(fromUserId, "收到语音消息, 但无法识别内容, 请发送文字消息");
                    continue;
                }

                log.info("语音转文字: {}", voiceText);

                // 大模型处理：将识别出的文字作为用户提问，获取回答文本
                String reply = llmService.chat(voiceText);

                // 文字回复
                client.sendText(fromUserId, reply);

                // 语音回复：将回答文本合成为 MP3 音频文件下发
                // 不调用平台原生语音气泡接口，避免账号风控封禁
                // 【费用提示】TTS语音合成按量计费，测试控制调用次数
                try {
                    byte[] audioBytes = llmService.textToSpeech(reply);
                    if (audioBytes != null && audioBytes.length > 0) {
                        String fileName = "reply_" + System.currentTimeMillis() + ".mp3";
                        client.sendFile(fromUserId, audioBytes, fileName, "语音回复");
                        log.info("语音文件已发送: {}", fileName);
                    } else {
                        log.warn("语音合成为空, 仅发送了文字回复");
                    }
                } catch (Exception e) {
                    log.warn("语音合成或发送失败, 仅发送了文字回复: {}", e.getMessage());
                }
                continue;
            }

            if (item.getVideo_item() != null) {
                log.info("收到视频消息 (暂不支持)");
                client.sendText(fromUserId, "收到视频消息, 暂不支持视频理解功能");
                continue;
            }

            if (item.getFile_item() != null) {
                String fileName = item.getFile_item().getFile_name();
                log.info("收到文件消息: {}", fileName);

                // 音频文件不做 API 识别，引导用户直接发语音气泡
                // 语音识别仅支持 iLink 协议自带的语音气泡转文字能力
                if (fileName != null && isAudioFile(fileName)) {
                    client.sendText(fromUserId, "请直接发送语音消息, 我可以识别语音内容并回复");
                } else {
                    client.sendText(fromUserId, "收到文件: " + fileName + ", 暂不支持文件解析功能");
                }
                continue;
            }
        }
    }

    /**
     * 图片+文字合并处理
     * 将缓存的图片与用户回复的文字描述一起送给多模态模型推理
     *
     * @param userId       用户ID
     * @param first        缓存的图片消息
     * @param secondText   用户回复的文字描述
     * @param secondImage  第二条消息图片（图片先于文字到达时不为null）
     */
    private void handleMergedMessages(String userId, PendingMessage first, String secondText, byte[] secondImage) {
        try {
            // 取图片：优先缓存中的图片，其次第二条消息的图片
            byte[] mergedImage = first.imageBytes != null ? first.imageBytes : secondImage;
            String mergedText = secondText;

            log.info("合并消息: text={}, hasImage={}", mergedText, mergedImage != null);

            if (mergedImage != null && mergedText != null) {
                // 有图片有文字：通过意图识别模块判断 subIntent（替代关键词匹配，更精准）
                IntentService.IntentResult mergedResult = intentService.recognize(mergedText);
                String mergedSubIntent = mergedResult.getSubIntent();
                log.info("合并消息意图识别: intent={}, subIntent={}", mergedResult.getIntent(), mergedSubIntent);

                if ("image_edit".equals(mergedSubIntent)) {
                    // 编辑图片（改背景、修改、替换等）：调用图生图 img2img 接口
                    handleImageEdit(userId, mergedImage, mergedText);
                } else if ("image_gen".equals(mergedSubIntent)) {
                    // 生成新图片：基于原图描述生成
                    handleImageGenerate(userId, mergedImage, mergedText);
                } else {
                    // 识别图片（描述、分析、是什么等）：视觉模型返回文字
                    String reply = llmService.chatWithImage(mergedImage, mergedText);
                    client.sendText(userId, reply);
                }
            } else if (mergedImage != null) {
                // 只有图片：描述图片
                String reply = llmService.chatWithImage(mergedImage, "请描述这张图片的内容");
                client.sendText(userId, reply);
            }
        } catch (Exception e) {
            log.error("合并消息处理失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "消息处理失败: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 判断是否为图片编辑意图
     * 关键词：改、换、变、修改、编辑、替换、背景、去掉、添加
     */
    private boolean isEditIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("改") || lower.contains("换") || lower.contains("变")
                || lower.contains("修改") || lower.contains("编辑") || lower.contains("替换")
                || lower.contains("背景") || lower.contains("去掉") || lower.contains("添加");
    }

    /**
     * 判断是否为图片生成意图
     * 关键词：生成、画、创造、做一个、弄一个
     */
    private boolean isGenerateIntent(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("生成") || lower.contains("画") || lower.contains("创造")
                || lower.contains("做一个") || lower.contains("弄一个");
    }

    /**
     * 处理图片编辑请求
     * 调用图生图 img2img 接口：需要传入用户上传原图资源，配置图片相似度参数，约束模型尽量保留原图主体，只做局部修改
     */
    private void handleImageEdit(String userId, byte[] imageBytes, String userInstruction) {
        try {
            client.sendText(userId, "正在编辑图片，请稍等...");
            byte[] editedImage = llmService.generateImageEdit(imageBytes, userInstruction);
            if (editedImage != null && editedImage.length > 0) {
                String fileName = "edit_" + System.currentTimeMillis() + ".png";
                client.sendImage(userId, editedImage, fileName, "编辑后的图片");
                log.info("编辑图片已发送: {}", fileName);
            } else {
                client.sendText(userId, "图片编辑失败，请稍后重试或换一个描述");
            }
        } catch (Exception e) {
            log.error("图片编辑失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "图片编辑失败: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 处理图片生成请求（基于原图）
     * 先理解原图，再根据用户描述生成新图片
     */
    private void handleImageGenerate(String userId, byte[] imageBytes, String userInstruction) {
        try {
            client.sendText(userId, "正在生成图片，请稍等...");
            // 先理解原图内容
            String description = llmService.chatWithImage(imageBytes,
                    "详细描述这张图片中的主体、风格、颜色等视觉细节");
            // 组合描述 + 用户指令生成新图片
            String prompt = description + "。" + userInstruction
                    + "。根据以上描述生成高质量图片，画面细节丰富，构图美观";
            byte[] newImage = llmService.generateImage(prompt);
            if (newImage != null && newImage.length > 0) {
                String fileName = "gen_" + System.currentTimeMillis() + ".png";
                client.sendImage(userId, newImage, fileName, "生成的图片");
                log.info("生成图片已发送: {}", fileName);
            } else {
                client.sendText(userId, "图片生成失败，请稍后重试或换一个描述");
            }
        } catch (Exception e) {
            log.error("图片生成失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "图片生成失败: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 处理纯文字消息（意图识别 + 分发）
     * 根据意图识别结果分流到不同处理逻辑：
     * - text:  普通聊天问答
     * - voice: 语音播报（文字+MP3文件）
     * - image: 进一步按 subIntent 细分
     *   - image_gen:  文生图（用户纯文字描述画图）
     *   - image_edit: 图生图 img2img（编辑已上传原图）
     */
    private void processTextMessage(String userId, String text) {
        IntentService.IntentResult intentResult = intentService.recognize(text);
        String intent = intentResult.getIntent();
        String subIntent = intentResult.getSubIntent();
        log.info("意图识别结果: intent={}, subIntent={}", intent, subIntent);

        switch (intent) {
            case "image":
                // 根据 subIntent 分流：image_gen 走文生图，image_edit 走图生图 img2img
                if ("image_edit".equals(subIntent)) {
                    handleImageEditIntent(userId, text);
                } else {
                    // image_gen 或未明确识别子意图，默认走文生图
                    handleImageIntent(userId, text, intentResult.getPrompt());
                }
                break;
            case "voice":
                handleVoiceIntent(userId, text);
                break;
            default:
                String reply = llmService.chat(text);
                try {
                    client.sendTextWithTyping(userId, reply, 1500L);
                } catch (Exception e) {
                    log.error("发送文字回复失败: {}", e.getMessage(), e);
                }
                break;
        }
    }

    /**
     * 处理图片生成意图
     * 调用通义万相 API 生成图片，通过 sendImage 发送给用户
     * @param userId 目标用户
     * @param userInput 用户原始输入
     * @param prompt 意图模块生成的画图提示词
     */
    private void handleImageIntent(String userId, String userInput, String prompt) {
        try {
            // 提示用户正在生成
            client.sendText(userId, "正在为你生成图片，请稍等...");

            // 调用大模型生成图片（异步任务，可能需要 10-30 秒）
            byte[] imageBytes = llmService.generateImage(prompt);
            if (imageBytes != null && imageBytes.length > 0) {
                // 生成成功，发送图片
                String fileName = "img_" + System.currentTimeMillis() + ".png";
                client.sendImage(userId, imageBytes, fileName, "为你生成的图片");
                log.info("图片已发送: {}", fileName);
            } else {
                // 生成失败
                client.sendText(userId, "图片生成失败，请稍后重试或换一个描述");
            }
        } catch (Exception e) {
            log.error("图片生成或发送失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "图片处理失败: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 处理图片编辑意图（image_edit）
     * 用户要求编辑已上传的图片，需要先获取用户上传的原图资源
     * - 检查 pendingMessageMap 是否有缓存的待处理图片
     * - 有图片：调用图生图 img2img 接口编辑
     * - 无图片：提示用户先发送图片
     *
     * @param userId    目标用户
     * @param userInput 用户编辑指令（如"把背景改成黑色"）
     */
    private void handleImageEditIntent(String userId, String userInput) {
        try {
            // 检查是否有用户上传的原图（图片消息到达时会缓存在 pendingMessageMap）
            PendingMessage pending = pendingMessageMap.remove(userId);
            if (pending == null || pending.imageBytes == null) {
                client.sendText(userId, "请先发送一张图片，我再帮你编辑");
                return;
            }

            client.sendText(userId, "正在编辑图片，请稍等...");

            // 调用图生图 img2img 接口：
            // 需要传入用户上传原图资源，配置图片相似度参数，约束模型尽量保留原图主体，只做局部修改
            byte[] editedImage = llmService.generateImageEdit(pending.imageBytes, userInput);
            if (editedImage != null && editedImage.length > 0) {
                String fileName = "edit_" + System.currentTimeMillis() + ".png";
                client.sendImage(userId, editedImage, fileName, "编辑后的图片");
                log.info("编辑图片已发送: {}", fileName);
            } else {
                client.sendText(userId, "图片编辑失败，请稍后重试或换一个描述");
            }
        } catch (Exception e) {
            log.error("图片编辑失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "图片编辑失败: " + e.getMessage());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 处理语音播报意图（tts_speak）
     * 用户发送指令如"用MP3音频给我打招呼"，意图识别为 voice/tts_speak
     *
     * 完整流程：
     * ①大模型生成朗读文本（从用户指令提取需要朗读的内容）
     * ②调用百炼TTS接口合成MP3二进制音频
     * ③把音频保存到本地临时文件
     * ④完成上传CDN存储（client.sendFile 内部完成CDN上传）
     * ⑤微信接口发送音频消息，推送MP3给微信用户
     *
     * 异常处理：
     * - TTS调用失败 → 返回"语音生成失败"
     * - CDN上传失败 → 返回"音频上传失败，请重试"
     *
     * 【费用提示】TTS语音合成按量计费，测试控制调用次数
     *
     * @param userId    目标用户
     * @param userInput 用户原始输入（如"用MP3音频给我打招呼"）
     */
    private void handleVoiceIntent(String userId, String userInput) {
        java.io.File tempFile = null;
        try {
            // ①大模型生成朗读文本（提取需要朗读的内容）
            String reply = llmService.chat(userInput);

            // 先发送文字回复
            client.sendText(userId, reply);

            // ②调用百炼TTS接口合成MP3音频
            // 【费用提示】TTS语音合成按量计费，测试控制调用次数
            byte[] audioBytes = llmService.textToSpeech(reply);
            if (audioBytes == null || audioBytes.length == 0) {
                // TTS合成失败，给用户返回文字提示
                log.warn("TTS合成返回空, userId={}", userId);
                client.sendText(userId, "语音生成失败");
                return;
            }

            // ③把音频保存到本地临时文件
            String fileName = "tts_" + System.currentTimeMillis() + ".mp3";
            try {
                tempFile = new java.io.File(System.getProperty("java.io.tmpdir"), fileName);
                java.nio.file.Files.write(tempFile.toPath(), audioBytes);
                log.info("TTS音频已保存到本地临时文件: {}, 大小: {} bytes", tempFile.getAbsolutePath(), audioBytes.length);
            } catch (Exception e) {
                log.error("保存临时文件失败: {}", e.getMessage(), e);
                client.sendText(userId, "语音生成失败");
                return;
            }

            // ④完成上传CDN存储 + ⑤微信接口发送音频消息
            // client.sendFile 内部完成CDN上传并推送MP3给微信用户
            try {
                client.sendFile(userId, audioBytes, fileName, "语音回复");
                log.info("语音文件已发送: {}", fileName);
            } catch (Exception e) {
                // CDN上传失败，给用户返回提示
                log.error("音频上传CDN失败: {}", e.getMessage(), e);
                client.sendText(userId, "音频上传失败，请重试");
            }
        } catch (Exception e) {
            log.error("语音播报失败: {}", e.getMessage(), e);
            try {
                client.sendText(userId, "语音生成失败");
            } catch (Exception ignored) {
            }
        } finally {
            // 清理本地临时文件
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
        }
    }

    /**
     * 判断文件名是否为音频文件
     * 支持 mp3/wav/m4a/aac/ogg/silk/amr 等常见音频格式
     */
    private boolean isAudioFile(String fileName) {
        if (fileName == null) {
            return false;
        }
        String lower = fileName.toLowerCase();
        return lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".m4a")
                || lower.endsWith(".aac") || lower.endsWith(".ogg") || lower.endsWith(".silk")
                || lower.endsWith(".amr");
    }
}
