package com.example.demo_wkx;

import com.example.demo_wkx.rag.RagService;
import com.example.demo_wkx.skill.SkillService;
import com.example.demo_wkx.service.LlmService;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.listener.OnMessageListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import javax.imageio.ImageIO;
import java.awt.Desktop;
import java.io.File;
import java.util.List;

@SpringBootApplication
public class DemoWkxApplication implements CommandLineRunner {

	@Autowired
	private LlmService llmService;

	@Autowired
	private SkillService skillService;

	@Autowired
	private RagService ragService;

	private ILinkClient client;

	private final java.util.Set<String> voiceModeUsers = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

	public static void main(String[] args) {
		SpringApplication.run(DemoWkxApplication.class, args);
	}

	@Override
	public void run(String... args) throws Exception {
		System.out.println("=== 微信 iLink Bot 启动中 ===");

		ILinkConfig config = ILinkConfig.builder()
				.heartbeatEnabled(true)
				.heartbeatIntervalMs(30000)
				.channelVersion("1.0.0")
				.build();

		client = ILinkClient.builder()
				.config(config)
				.onLogin(new OnLoginListener() {
					@Override
					public void onLoginSuccess(LoginContext context) {
						System.out.println("\n✅ 登录成功！botId = " + context.getBotId());
						System.out.println("📡 等待接收消息...\n");
					}

					@Override
					public void onLoginFailure(Throwable throwable) {
						System.err.println("❌ 登录失败: " + throwable.getMessage());
					}
				})
				.onMessage(new OnMessageListener() {
					@Override
					public void onMessages(List<WeixinMessage> messages) {
						for (WeixinMessage msg : messages) {
							Thread.startVirtualThread(() -> handleMessage(msg));
						}
					}
				})
				.build();

		String qrCodeContent = client.executeLogin();
		System.out.println("\n📱 请扫描二维码登录微信：\n");
		showQrCode(qrCodeContent);

		client.getLoginFuture().get();

		Runtime.getRuntime().addShutdownHook(new Thread(this::cleanup));
	}

	private void handleMessage(WeixinMessage msg) {
		try {
			String fromUserId = msg.getFrom_user_id();
			if (msg.getItem_list() == null) return;

			for (MessageItem item : msg.getItem_list()) {
				if (item.getText_item() != null) {
					String text = item.getText_item().getText();
					System.out.println("📨 收到消息 [" + fromUserId + "]: " + text);

					String trimmed = text.trim();

					if (trimmed.startsWith("画") || trimmed.toLowerCase().startsWith("image:")) {
						handleImageCommand(fromUserId, trimmed);
					} else if (trimmed.equals("语音模式")) {
						voiceModeUsers.add(fromUserId);
						client.sendText(fromUserId, "🔊 已切换到语音模式，我的回复将以语音发送。发送\"文字模式\"切换回文字。");
					} else if (trimmed.equals("文字模式")) {
						voiceModeUsers.remove(fromUserId);
						client.sendText(fromUserId, "✅ 已切换到文字模式。");
					} else if (trimmed.startsWith("说:")) {
						handleVoiceCommand(fromUserId, trimmed);
					} else if (trimmed.equals("清空") || trimmed.equals("重置")) {
						llmService.clearHistory(fromUserId);
						client.sendText(fromUserId, "✅ 对话已清空，可以开始新的对话。");
					} else if (trimmed.equals("帮助") || trimmed.equalsIgnoreCase("help")) {
						sendHelp(fromUserId);
					} else if (trimmed.equals("rag开关") || trimmed.equals("rag toggle")) {
						boolean newState = !ragService.isEnabled();
						ragService.setEnabled(newState);
						client.sendText(fromUserId, "📚 RAG 已" + (newState ? "开启" : "关闭") +
							"，知识库共 " + ragService.getDocumentCount() + " 篇文档。");
					} else if (trimmed.equals("rag状态") || trimmed.equals("rag status")) {
						client.sendText(fromUserId, "📚 RAG 状态：" + (ragService.isEnabled() ? "✅ 开启" : "❌ 关闭") +
							"\n知识库文档数：" + ragService.getDocumentCount() + "\n" +
							"消息路由流程：Skill → RAG → LLM");
					} else {
						String reply = routeMessage(fromUserId, text, false);
						System.out.println("🤖 回复 [" + fromUserId + "]: " + reply);
						routeReply(fromUserId, reply, false);
					}
				} else if (item.getImage_item() != null) {
					System.out.println("🖼️ 收到图片消息 [" + fromUserId + "]");
					try {
						byte[] imageBytes = client.downloadImageFromMessageItem(item);
						System.out.println("📥 图片下载完成，大小: " + (imageBytes != null ? imageBytes.length : 0) + " bytes");
						String description = llmService.describeImage(imageBytes);
						System.out.println("🤖 图片分析结果 [" + fromUserId + "]: " + description);
						String contextualMsg = "用户发送了一张图片，图片内容：" + description;
						String reply = llmService.chat(fromUserId, contextualMsg, false);
						System.out.println("🤖 回复 [" + fromUserId + "]: " + reply);
						routeReply(fromUserId, reply, false);
						System.out.println("✅ 图片回复已发送给 " + fromUserId);
					} catch (Exception ex) {
						System.err.println("❌ 图片处理失败: " + ex.getMessage());
						ex.printStackTrace();
						try {
							client.sendText(fromUserId, "收到你的图片，但处理时出错: " + ex.getMessage());
						} catch (Exception ignored) {}
					}
				} else if (item.getVoice_item() != null) {
					System.out.println("🔊 收到语音消息 [" + fromUserId + "]");
					try {
						String voiceText = item.getVoice_item().getText();
						System.out.println("📝 SDK文字: " + (voiceText != null ? voiceText : "(空)"));

						if (voiceText == null || voiceText.isEmpty()) {
							byte[] voiceBytes = client.downloadVoiceFromMessageItem(item);
							System.out.println("📥 语音下载完成，大小: " + (voiceBytes != null ? voiceBytes.length : 0) + " bytes");

							Integer encodeType = item.getVoice_item().getEncode_type();
							String format = (encodeType != null && encodeType == 4) ? "mp3" : "amr";
							System.out.println("🎵 语音格式: " + format + ", encode_type: " + encodeType);

							voiceText = llmService.transcribeAudio(voiceBytes, format);
							System.out.println("📝 ASR识别结果: " + (voiceText != null ? voiceText : "(失败)"));
						}

						if (voiceText != null && !voiceText.isEmpty()) {
							System.out.println("💬 语音内容: " + voiceText);
							String reply = routeMessage(fromUserId, voiceText, true);
							System.out.println("🤖 回复 [" + fromUserId + "]: " + reply);
							routeReply(fromUserId, reply, true);
						} else {
							client.sendText(fromUserId, "收到你的语音，但语音识别失败。请用文字发送消息。");
							System.out.println("⚠ 语音识别失败，已通知用户");
						}
					} catch (Exception ex) {
						System.err.println("❌ 语音处理失败: " + ex.getMessage());
						ex.printStackTrace();
						try {
							client.sendText(fromUserId, "收到你的语音，但处理时出错: " + ex.getMessage());
						} catch (Exception ignored) {}
					}
				} else {
					System.out.println("❓ 收到未知类型消息 [" + fromUserId + "], item类型: " + item.getClass().getName());
					System.out.println("   item内容: " + item.toString());
				}
			}
		} catch (Exception e) {
			System.err.println("⚠ 处理消息异常: " + e.getMessage());
		}
	}

	/**
	 * 消息路由核心逻辑：Skill → RAG → LLM 兜底
	 */
	private String routeMessage(String fromUserId, String text, boolean fromVoice) {
		String skillResult = skillService.tryMatch(text);
		if (skillResult != null) {
			System.out.println("🎯 [路由] Skill 命中，直接回复");
			return skillResult;
		}

		String ragContext = ragService.retrieve(text);
		if (ragContext != null) {
			System.out.println("🔍 [路由] RAG 命中，增强 Prompt 后调用 LLM");
			return llmService.chat(fromUserId, text, fromVoice, ragContext);
		}

		System.out.println("💬 [路由] Skill/RAG 均未命中，LLM 闲聊兜底");
		return llmService.chat(fromUserId, text, fromVoice);
	}

	private void routeReply(String fromUserId, String reply, boolean fromVoice) {
		try {
			String intent = "TEXT";
			String content = reply;

			if (reply.startsWith("[IMAGE:")) {
				int end = reply.indexOf("]");
				if (end > 0) {
					intent = "IMAGE";
					content = reply.substring(7, end).trim();
				}
			} else if (reply.startsWith("[VOICE]")) {
				intent = "VOICE";
				content = reply.substring(7).trim();
			} else if (reply.startsWith("[TEXT]")) {
				intent = "TEXT";
				content = reply.substring(6).trim();
			}

			if (fromVoice && intent.equals("TEXT")) {
				intent = "VOICE";
			}

			if (intent.equals("TEXT") && voiceModeUsers.contains(fromUserId)) {
				intent = "VOICE";
			}

			System.out.println("🎯 意图: " + intent + " (fromVoice=" + fromVoice + ", voiceMode=" + voiceModeUsers.contains(fromUserId) + ")");

			switch (intent) {
				case "IMAGE" -> handleImageCommand(fromUserId, content);
				case "VOICE" -> sendReply(fromUserId, content, true);
				default -> client.sendText(fromUserId, content);
			}
		} catch (Exception e) {
			System.err.println("❌ 路由回复失败: " + e.getMessage());
			try { client.sendText(fromUserId, reply); } catch (Exception ignored) {}
		}
	}

	private void sendReply(String fromUserId, String reply) {
		sendReply(fromUserId, reply, false);
	}

	private void sendReply(String fromUserId, String reply, boolean forceVoice) {
		try {
			boolean useVoice = forceVoice || voiceModeUsers.contains(fromUserId);
			if (useVoice) {
				System.out.println("🔊 语音模式，尝试TTS...");
				byte[] mp3Bytes = llmService.textToSpeech(reply);
				if (mp3Bytes != null && mp3Bytes.length > 0) {
					try {
						client.sendText(fromUserId, "🔊 语音回复（MP3文件）：");
						client.sendFile(fromUserId, mp3Bytes, "语音回复.mp3", null);
						System.out.println("✅ 语音回复(MP3文件)已发送给 " + fromUserId + " (" + mp3Bytes.length + " bytes)");
					} catch (Exception e) {
						System.out.println("⚠ 语音文件发送失败，回退文字: " + e.getMessage());
						e.printStackTrace();
						client.sendText(fromUserId, reply);
					}
				} else {
					System.out.println("⚠ TTS失败，回退文字");
					client.sendText(fromUserId, reply);
				}
			} else {
				client.sendText(fromUserId, reply);
			}
		} catch (Exception e) {
			System.err.println("❌ 回复失败: " + e.getMessage());
			try { client.sendText(fromUserId, reply); } catch (Exception ignored) {}
		}
	}

	private void handleImageCommand(String fromUserId, String text) throws Exception {
		String prompt = text.replaceFirst("^(画\\s*:?\\s*|image:)", "").trim();
		if (prompt.isEmpty()) {
			client.sendText(fromUserId, "请输入要画的内容，例如：画 一只可爱的猫咪");
			return;
		}
		System.out.println("🎨 正在生成图片: " + prompt);
		client.sendText(fromUserId, "正在为你生成图片: " + prompt + " ...");

		byte[] imageBytes = llmService.generateImage(prompt);
		if (imageBytes != null && imageBytes.length > 0) {
			client.sendImage(fromUserId, imageBytes, "ai_generated.png", "AI生成图片: " + prompt);
			System.out.println("✅ 图片已发送给 " + fromUserId);
		} else {
			client.sendText(fromUserId, "图片生成失败，请稍后重试。");
		}
	}

	private void handleVoiceCommand(String fromUserId, String text) throws Exception {
		String content = text.replaceFirst("^说:\\s*", "").trim();
		if (content.isEmpty()) {
			client.sendText(fromUserId, "请输入要说的话，例如：说 你好世界");
			return;
		}
		System.out.println("🔊 语音合成: " + content);
		byte[] mp3Bytes = llmService.textToSpeech(content);
		if (mp3Bytes != null && mp3Bytes.length > 0) {
			try {
				client.sendText(fromUserId, "🔊 语音（MP3文件）：");
				client.sendFile(fromUserId, mp3Bytes, "语音.mp3", null);
				System.out.println("✅ 语音(MP3文件)已发送给 " + fromUserId);
			} catch (Exception e) {
				System.out.println("⚠ 语音文件发送失败，回退文字: " + e.getMessage());
				e.printStackTrace();
				client.sendText(fromUserId, content);
			}
		} else {
			client.sendText(fromUserId, "语音合成失败，请稍后重试。");
		}
	}

	private void sendHelp(String fromUserId) throws Exception {
		String help = """
				🤖 微信AI助手 - 功能列表

				1. 智能对话 - 直接发消息，AI自动判断回复方式
				2. 语音对话 - 发语音消息，AI语音回复（MP3文件）
				3. 语音模式 - 发"语音模式"强制语音回复，"文字模式"切回
				4. 图片生成 - 发"画 <描述>"或直接说"画一只猫"
				5. 图片分析 - 发图片，AI分析内容
				6. 天气查询 - 问"北京天气怎么样"，AI自动调用天气工具
				7. 时间查询 - 问"现在几点"，AI自动调用时间工具
				8. 星座运势 - 发"运势"或"今日运势"查看星座运势（自定义Skill）
				9. RAG开关 - 发"rag开关"开启/关闭RAG增强
				10. RAG状态 - 发"rag状态"查看RAG当前状态
				11. 清空对话 - 发"清空"重置对话历史
				12. 帮助 - 发"帮助"查看此菜单

				🔧 支持 Function Calling：AI自动判断是否调用工具
				🎯 自定义Skill：关键词命中直接执行（星座运势）
				📚 RAG检索增强：知识库关键词匹配，增强LLM回答
				🛤️ 消息路由：Skill → RAG → LLM 三级路由
				🧠 AI会自动判断用文字、语音还是图片回复
				📅 支持农历日期、星座四象分类查询
				🔊 语音回复以MP3文件形式发送，点击可播放
				""";
		client.sendText(fromUserId, help);
	}

	private void showQrCode(String content) {
		try {
			QRCodeWriter qrCodeWriter = new QRCodeWriter();
			BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, 400, 400);

			File qrFile = new File(System.getProperty("java.io.tmpdir"), "wechat_ilink_qr.png");
			MatrixToImageWriter.writeToPath(bitMatrix, "PNG", qrFile.toPath());

			System.out.println("二维码已保存: " + qrFile.getAbsolutePath());

			if (Desktop.isDesktopSupported()) {
				Desktop.getDesktop().open(qrFile);
				System.out.println("已自动打开二维码图片，请用微信扫码登录\n");
			}
		} catch (Exception e) {
			System.err.println("生成二维码图片失败: " + e.getMessage());
			System.out.println("二维码内容(可复制到在线二维码生成器):");
			System.out.println(content);
		}
	}

	@PreDestroy
	public void cleanup() {
		if (client != null) {
			try {
				client.close();
				System.out.println("iLink客户端已关闭");
			} catch (Exception e) {
				System.err.println("关闭客户端异常: " + e.getMessage());
			}
		}
	}
}
