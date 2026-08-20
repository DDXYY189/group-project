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

语音消息支持 ASR 转写、edge-tts 合成，并以 MP3 文件回复，失败时自动回退为文本。

已内置工具：天气查询、待办管理、当前时间、联网搜索、翻译、随机数、查单词、热点资讯、清除记忆。对话记忆支持长时摘要与 TTL 过期。

## 多步工具链

`ToolChainService` 支持确定性的工具链式调用：下一步的参数模板通过 `{{prev.xxx}}` 引用上一步的执行结果，任一步失败立即中断。已注册两条链：

- `weather_to_todo`：`query_weather` -> `manage_todo`，把城市天气自动记入待办
- `hot_news_to_todo`：`get_hot_news` -> `web_search` -> `manage_todo`，把第一条热点搜出详情后记入待办

手动验证接口：

```powershell
# 查看已注册的链
Invoke-RestMethod http://localhost:8080/api/bot/tool-chains

# 运行天气 -> 待办链（body 是链入口参数）
Invoke-RestMethod -Method Post -ContentType "application/json" `
  -Body '{"location":"北京"}' `
  "http://localhost:8080/api/bot/tool-chains/weather_to_todo/run?userId=demo"
```

对话测试提示词：

- `查询北京天气，然后把天气记到我的待办里`
- `看看今天有什么热点，把第一条热点加到我的待办里`

链式流程完成后机器人会直接回复每一步的执行结果，待办内容来自上一步工具的返回结果。
