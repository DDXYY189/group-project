package com.youkeda.wechatbotdemo;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.VoiceItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信机器人主程序：
 * 1. 二维码扫码登录
 * 2. 循环拉取用户消息
 * 3. 把消息（文字 / 语音转文字）交给通义千问（阿里云百炼）生成回复
 * 4. 自动把回复发回给用户
 */
public class QuickStartExample {

    public static void main(String[] args) throws Exception {
        // 初始化大模型服务（会自动读取 API Key）
        LlmService llmService = new LlmService();
        ImageService imageService = new ImageService(llmService.getApiKey());
        VoiceService voiceService = new VoiceService(llmService.getApiKey());
        WeatherService weatherService = new WeatherService();

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

        ILinkClient client = ILinkClient.builder()
                .config(config)
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext context) {
                        System.out.println("登录成功，botId = " + context.getBotId());
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        System.err.println("登录失败: " + throwable.getMessage());
                    }
                })
                .build();

        try {
            // 第一步：登录
            String qrCodeContent = client.executeLogin();
            System.out.println("==============================================");
            System.out.println("请把下面的内容生成二维码，用微信扫码登录：");
            System.out.println(qrCodeContent);
            System.out.println("==============================================");

            LoginContext context = client.getLoginFuture().get();
            System.out.println("登录完成，botId = " + context.getBotId());
            System.out.println("机器人已启动，等待用户消息…（Ctrl+C 退出）");

            // 第二步：循环拉取消息 + 自动回复
            while (true) {
                List<WeixinMessage> messages = client.getUpdates();

                for (WeixinMessage msg : messages) {
                    String fromUser = msg.getFrom_user_id();
                    IncomingContent content = extractContent(msg);

                    // 跳过没有内容的消息（比如图片、视频、文件等暂不处理的类型）
                    if (fromUser == null || content == null) {
                        continue;
                    }

                    System.out.println("收到 [" + fromUser + "] " + content.type.label + ": " + content.text);

                    try {
                        byte[] directImage = null; // Function Calling 里图片工具直接生成的图
                        String reply;
                        if (content.type == MessageType.VOICE_UNRECOGNIZED) {
                            // 收到语音但服务端没返回转写文字，友好提示
                            reply = "收到你的语音啦～但我这边暂时无法识别语音内容，麻烦用文字发给我哦。";
                        } else {
                            // 文字消息 / 语音已转文字 → 都交给大模型
                            String prompt = content.type == MessageType.VOICE
                                    ? "（用户发来一条语音消息，内容已自动转成文字）" + content.text
                                    : content.text;

                            // 1. 老规则：先看是不是查天气（如 "杭州天气"），是则直接查心知天气，不走大模型
                            String city = extractWeatherCity(content.text);
                            if (city != null) {
                                reply = weatherService.queryWeather(city);
                                System.out.println("天气查询 [" + fromUser + "]: " + city);
                            } else {
                                // 2. Function Calling：大模型自己决定调哪个工具（查单词/随机数/天气/图片）
                                LlmService.ChatResult result = llmService.chatWithTools(prompt);
                                if (result.imageBytes != null) {
                                    directImage = result.imageBytes;
                                    reply = null;
                                } else {
                                    reply = result.text;
                                }
                            }
                        }

                        // 第三步：根据回复类型发回给用户
                        if (directImage != null) {
                            // Function Calling 的图片工具已直接生成图片 → 直接发图，不再发文字
                            client.sendImage(fromUser, directImage, "generated.png", "这是按你要求生成的图片～");
                            System.out.println("回复 [" + fromUser + "]: 已发送图片(Function Calling)");
                        } else {
                            String voiceText = extractVoiceText(reply);
                            String imagePrompt = extractImagePrompt(reply);

                            if (voiceText != null && !voiceText.isBlank()) {
                                // 大模型要求发语音
                                client.sendText(fromUser, "语音正在合成中，稍等片刻哦～");
                                try {
                                    VoiceService.SilkVoiceResult voice = voiceService.synthesizeToSilk(voiceText);
                                    client.sendVoice(fromUser, voice.silkBytes, "voice.silk", voice.playTimeMs, voice.sampleRate);
                                    System.out.println("回复 [" + fromUser + "]: 已发送语音，内容=" + voiceText);
                                } catch (Exception voiceErr) {
                                    System.err.println("语音合成失败: " + voiceErr.getMessage());
                                    client.sendText(fromUser, "语音合成失败啦，先跟你说：" + reply);
                                }
                            } else if (imagePrompt != null && !imagePrompt.isBlank()) {
                                // 大模型要求发图片（[IMAGE:xxx] 标记方式）
                                client.sendText(fromUser, "图片正在生成中，稍等片刻哦～");
                                try {
                                    byte[] imageBytes = imageService.generateImage(imagePrompt);
                                    client.sendImage(fromUser, imageBytes, "generated.png", "这是按你要求生成的图片～");
                                    System.out.println("回复 [" + fromUser + "]: 已发送图片，提示词=" + imagePrompt);
                                } catch (Exception imgErr) {
                                    System.err.println("图片生成失败: " + imgErr.getMessage());
                                    client.sendText(fromUser, "图片生成失败啦，先跟你聊聊：" + reply);
                                }
                            } else {
                                client.sendText(fromUser, reply);
                                System.out.println("回复 [" + fromUser + "]: " + reply);
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("处理消息失败: " + e.getMessage());
                    }
                }
            }
        } finally {
            client.close();
        }
    }

    /** 消息类型 */
    private enum MessageType {
        TEXT("文字"),
        VOICE("语音(已转文字)"),
        VOICE_UNRECOGNIZED("语音(未识别)");

        final String label;

        MessageType(String label) {
            this.label = label;
        }
    }

    /** 从一条消息里提取出的内容 */
    private static final class IncomingContent {
        final MessageType type;
        final String text;

        IncomingContent(MessageType type, String text) {
            this.type = type;
            this.text = text;
        }
    }

    private static final Pattern IMAGE_PATTERN = Pattern.compile("\\[IMAGE:(.*?)\\]");
    private static final Pattern VOICE_PATTERN = Pattern.compile("\\[VOICE:(.*?)\\]");
    private static final Pattern WEATHER_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{2,6})天气");

    /**
     * 从用户消息中提取要查询天气的城市。
     * 匹配 "xx天气" 格式（xx 为 2-6 个汉字），如 "杭州天气"、"今天临汾的天气"。
     * 先清洗掉常见的时间词、前缀词，避免它们被误吞进城市名（如 "下今天临汾的天气" 误提为 "下临汾"）。
     * 匹配不到城市（如 "今天天气怎么样"）返回 null，走大模型正常对话。
     */
    private static String extractWeatherCity(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        // 先把时间词、语气词、常见前缀词去掉
        String cleaned = text.replaceAll(
                "今天|明天|后天|昨天|现在|最近|这周|下周|早上|晚上|上午|下午|"
                        + "帮我|查询|查一下|查查看|查下|查|看看|看一下|一下|顺便|"
                        + "我想|想知道|哪里|当地|那边|的|吗|呢|吧|请",
                "");
        Matcher matcher = WEATHER_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1).trim();
    }

    /**
     * 从大模型回复中提取图片提示词。如果回复包含 [IMAGE:<提示词>] 标记，返回提示词；否则返回 null。
     */
    private static String extractImagePrompt(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        Matcher matcher = IMAGE_PATTERN.matcher(reply);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 从大模型回复中提取要朗读的语音内容。如果回复包含 [VOICE:<内容>] 标记，返回内容；否则返回 null。
     */
    private static String extractVoiceText(String reply) {
        if (reply == null || reply.isBlank()) {
            return null;
        }
        Matcher matcher = VOICE_PATTERN.matcher(reply);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * 从一条微信消息里提取文字内容：
     * - 文字消息 → 直接返回文本
     * - 语音消息 → 优先返回服务端自动转写好的文字（voice_item.text），
     *   转写为空时返回 VOICE_UNRECOGNIZED，用于友好提示
     * - 图片 / 视频 / 文件 → 返回 null（暂不处理）
     */
    private static IncomingContent extractContent(WeixinMessage msg) {
        if (msg.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : msg.getItem_list()) {
            // 1. 文字消息
            if (item.getText_item() != null && item.getText_item().getText() != null
                    && !item.getText_item().getText().isBlank()) {
                return new IncomingContent(MessageType.TEXT, item.getText_item().getText());
            }
            // 2. 语音消息：SDK 服务端会自动把收到的语音转成文字（voice_item.text）
            VoiceItem voice = item.getVoice_item();
            if (voice != null) {
                String voiceText = voice.getText();
                if (voiceText != null && !voiceText.isBlank()) {
                    return new IncomingContent(MessageType.VOICE, voiceText);
                }
                return new IncomingContent(MessageType.VOICE_UNRECOGNIZED, null);
            }
        }
        return null;
    }
}
