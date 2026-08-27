# demo-dxy

微信 iLink Bot 骨架工程（组长分支 `feature/dxy`）。

## 运行

```bash
mvn spring-boot:run
```

启动后：

- 前端会自动创建会话并扫码；手动接口：
  - `POST /api/bot/session`：创建会话，返回 `sessionId`
  - `GET /api/bot/session/{id}/qr.png`：获取该会话的登录二维码
  - `GET /api/bot/session/{id}/status`：查看该会话登录状态
  - `POST /api/bot/session/{id}/relogin`：重新登录
  - `DELETE /api/bot/session/{id}`：关闭并删除会话
  - `GET /api/bot/sessions`：查看全部会话
- 扫码登录后，给机器人发文本，会收到 `收到：<原文>` 的回复

## 多用户会话

每个 `sessionId` 对应一个独立的 `ILinkClient`，支持多个用户同时扫码，登录过程由共享线程池异步执行，互不阻塞。
登录成功后通过 `exportResumeContext()` 将登录信息存入 H2（`bot_session` 表），服务重启后自动恢复，无需重新扫码；token 失效时在页面点“重新登录”即可。

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

## MCP 能力接入

项目已接入官方 Java MCP SDK，支持通过 `application.properties` 配置 stdio、SSE 和 Streamable HTTP 三类 MCP Server。远程工具会自动注册成 `mcp_<server>_<tool>` 形式的本地工具，微信对话中可直接调用。

默认已启用内置 MCP 演示 Server（A2 方案）：应用启动后在 `/mcp/demo` 只暴露 `demo_echo`、`demo_current_time`、`demo_add` 三个 MCP 专用演示工具，避免工具列表过多。客户端会自动连接它，控制台 MCP 面板可直接看到连接状态和已接入工具，无需安装 Node/Python。

```properties
mcp.enabled=true
mcp.demo-server-enabled=true
mcp.servers.demo.type=http
mcp.servers.demo.url=http://localhost:8080
mcp.servers.demo.endpoint=/mcp/demo
# mcp.servers.everything.type=stdio
# mcp.servers.everything.command=npx
# mcp.servers.everything.args=-y,@modelcontextprotocol/server-everything
```

调试接口：

```powershell
Invoke-RestMethod http://localhost:8080/api/bot/mcp/status
Invoke-RestMethod -Method Post http://localhost:8080/api/bot/mcp/reload
```

协议说明见 [docs/mcp.md](docs/mcp.md)。

## 定时任务与提醒

内置两类定时任务：

- 每日热点推送：按 `scheduler.daily-news-cron` 定时向联系过机器人的用户推送新闻
- 用户自定义提醒：支持一次性、每天、Cron 三种类型，H2 持久化，机器人会主动发送到微信

微信里可以直接说“提醒我明天早上 9 点开会”，模型会调用 `manage_reminder` 工具创建提醒。

控制台已增加“定时任务”面板，可创建、查看、删除提醒。

配置项：

- `scheduler.enabled`：总开关，默认 `true`
- `scheduler.daily-news-cron`：每日新闻 Cron，默认 `0 30 8 * * *`
- `scheduler.reminder-poll-ms`：提醒扫描间隔，默认 `15000`
- `scheduler.timezone`：时区，默认 `Asia/Shanghai`

调试接口：

```powershell
# 查看调度状态
Invoke-RestMethod http://localhost:8080/api/bot/scheduled/status

# 查看全部提醒
Invoke-RestMethod http://localhost:8080/api/bot/reminders

# 创建一次性提醒
Invoke-RestMethod -Method Post -ContentType "application/json" `
  -Body '{"userId":"demo","content":"明天开会","scheduleType":"once","fireAt":"2026-08-27 09:00"}' `
  http://localhost:8080/api/bot/reminders

# 删除提醒
Invoke-RestMethod -Method Delete "http://localhost:8080/api/bot/reminders/1?userId=demo"
```

## Skill 路由

新增 `skill` 包与 `router` 包，文本消息统一走 `MessageRouter`：

- 命中 Skill 关键词 -> Skill 执行
- 未命中 -> 命中 RAG 关键词 -> 增强 Prompt -> LLM 回复
- 都没命中 -> LLM 工具对话兜底

`Skill` 接口支持两种执行模式：

- 直接执行：`directReply()` 返回 true，命中后调用 `execute()` 返回结果
- LLM 驱动：把 `instructions()` 注入系统提示词，并只开放 `allowedTools()` 声明的工具

`SkillRegistry` 启动时自动收集所有 Skill Bean，新增技能只需新增一个实现类，不需要改注册代码。

已内置技能：

- `travel_planner` 旅行规划：关键词为旅游、旅行、攻略、行程等，注入规划指令，只开放 `web_search`、`query_weather`、`manage_todo` 三个工具

调试接口：

```powershell
# 查看已注册技能
Invoke-RestMethod http://localhost:8080/api/bot/skills

# 测试路由：命中技能/兜底闲聊
Invoke-RestMethod "http://localhost:8080/api/bot/route?q=帮我规划成都三日游&userId=demo"
```

## RAG 关键词检索

极简关键词版 RAG：启动时加载 `src/main/resources/knowledge/*.md`，按段落切块后用中英文关键词构建倒排索引，查询时按命中次数返回 top-k 资料块，并作为“参考资料”注入系统提示词。

配置项：

- `rag.enabled`：是否启用 RAG，默认 `true`
- `rag.top-k`：最多返回几个资料块，默认 `3`

调试接口：

```powershell
# 查看 RAG 开关、知识块数量
Invoke-RestMethod http://localhost:8080/api/bot/rag/status

# 开启/关闭 RAG（对比测试）
Invoke-RestMethod -Method Post "http://localhost:8080/api/bot/rag/toggle?enabled=false"
Invoke-RestMethod -Method Post "http://localhost:8080/api/bot/rag/toggle?enabled=true"

# 查看关键词检索命中结果
Invoke-RestMethod "http://localhost:8080/api/bot/rag/search?q=校庆是什么时候"
```

对比测试：在 `rag.enabled=false` 与 `rag.enabled=true` 两种状态下问同一个问题，例如“学校校庆是什么时候”，关闭时模型只能凭自身知识回答，开启时 prompt 会包含知识库中的“11 月 18 日”资料。

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

## 长任务旅行规划 Agent

旅行 Skill 已升级为长任务 Agent：用户只输入一句话目标，Agent 自动拆解并执行整条链路，最终产出一份完整成品。

示例输入：

```text
预算 5000，帮我规划 4 月 1-3 号上海 3 日游
```

Agent 自动执行步骤：

1. 解析目的地、天数、预算、日期、同行人和偏好；关键信息缺失时先补问一句
2. 调用 `query_weather` 查询目的地天气
3. 调用 `web_search` 搜索交通、景点、住宿和美食资料
4. 检索本地 RAG 知识库 `knowledge/travel.md`，把旅行经验注入行程生成
5. 让 LLM 生成结构化 JSON 行程方案
6. 渲染并保存完整旅行网页，输出 `/api/trips/{id}.html` 链接
7. 把每日行程和必做事项写入 `manage_todo`
8. 可选生成封面图（`image`）和语音摘要（`voice`），失败时自动跳过，不影响成品

调试接口：

```powershell
# 完整跑一遍长任务 Agent
Invoke-RestMethod "http://localhost:8080/api/bot/travel-agent?q=帮我规划上海3日游&userId=demo"

# 查看已生成的旅行网页
Invoke-WebRequest "http://localhost:8080/api/trips/<pageId>.html"
```

在微信里直接发“帮我规划xx三日游”也会命中该 Skill，回复会带上网页链接。网页链接默认使用 `http://localhost:8080/api/trips`，如果要让微信/手机访问，把 `application.properties` 里的 `travel.page-base-url` 改成局域网或公网地址。

配置项：

- `travel.page-dir`：网页文件保存目录，默认 `data/trips`
- `travel.page-base-url`：网页链接前缀，默认 `http://localhost:8080/api/trips`
- `travel.generate-image`：是否生成封面图，默认 `true`
- `travel.generate-voice`：是否生成语音摘要，默认 `true`
