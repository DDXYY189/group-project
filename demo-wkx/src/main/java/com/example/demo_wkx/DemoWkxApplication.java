package com.example.demo_wkx;

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

	private ILinkClient client;

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
					} else if (trimmed.startsWith("语音") || trimmed.startsWith("说:")) {
						handleVoiceCommand(fromUserId, trimmed);
					} else if (trimmed.equals("清空") || trimmed.equals("重置")) {
						llmService.clearHistory(fromUserId);
						client.sendText(fromUserId, "✅ 对话已清空，可以开始新的对话。");
					} else if (trimmed.equals("帮助") || trimmed.equalsIgnoreCase("help")) {
						sendHelp(fromUserId);
					} else {
						String reply = llmService.chat(fromUserId, text);
						System.out.println("🤖 回复 [" + fromUserId + "]: " + reply);
						client.sendText(fromUserId, reply);
					}
				}
			}
		} catch (Exception e) {
			System.err.println("⚠ 处理消息异常: " + e.getMessage());
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
		client.sendText(fromUserId, "🔊 语音功能正在开发中，敬请期待！\n当前支持的功能：文本对话、图片生成");
	}

	private void sendHelp(String fromUserId) throws Exception {
		String help = """
				🤖 微信AI助手 - 功能列表

				1. 文本对话 - 直接发送消息即可与AI对话
				2. 图片生成 - 发送 "画 <描述>" 生成图片
				   例如: 画 一只可爱的猫咪
				3. 清空对话 - 发送 "清空" 重置对话历史
				4. 帮助 - 发送 "帮助" 查看此菜单

				🔊 语音功能开发中...
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
