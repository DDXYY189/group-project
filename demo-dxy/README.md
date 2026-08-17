# demo-dxy

微信 iLink Bot 骨架工程（组长分支 `feature/dxy`）。

## 运行

```bash
mvn spring-boot:run
```

启动后：

- `GET http://localhost:8080/api/bot/qr.png`：获取登录二维码
- `GET http://localhost:8080/api/bot/status`：查看登录状态
- 扫码登录后，给机器人发文本，会收到 `收到：<原文>` 的回复

## 技术栈

- Java 21
- Spring Boot 4.1.0
- wechat-ilink-sdk 2.3.3

## LLM 接入（阿里云百炼 / 千问）

```powershell
$env:DASHSCOPE_API_KEY="sk-你的key"
mvn spring-boot:run
```

配置项：

- `llm.model`：文本模型，默认 `qwen-plus`
- `llm.vision-model`：图片理解模型，默认 `qwen-vl-plus`
- `llm.base-url`：OpenAI 兼容地址，默认 `https://dashscope.aliyuncs.com/compatible-mode/v1`

语音消息目前只回复占位提示，ASR/TTS + SILK 转换链路待接入。
