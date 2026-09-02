# 微信智能助手 — 基于 LLM + MCP 的多 Agent 应用

> 一个接入微信的 AI 助手：扫码即用，支持多用户同时在线；内置天气、待办、搜索等 10+ 工具，集成阿里云百炼大模型与 MCP 协议；亮点功能是**旅行规划长任务 Agent**——一句话生成含可交互地图、酒店美食推荐、语音摘要的完整行程网页。

<p>
  <img src="https://img.shields.io/badge/Java-21-orange" />
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1.0-green" />
  <img src="https://img.shields.io/badge/Tests-108%20passed-brightgreen" />
  <img src="https://img.shields.io/badge/License-MIT-blue" />
</p>

---

## 架构总览

```
┌─────────────────────────────────────────────────────────────┐
│                        微信用户 (多端)                        │
│                    扫码登录 / 文字·语音对话                     │
└──────────────────────────┬──────────────────────────────────┘
                           │ wechat-ilink-sdk
┌──────────────────────────▼──────────────────────────────────┐
│                     Spring Boot 应用                         │
│  ┌───────────┐   ┌──────────────┐   ┌────────────────────┐  │
│  │  会话管理   │   │  MessageRouter│   │   控制台 (静态页)    │  │
│  │ H2 持久化   │   │ Skill→RAG→LLM│   │  会话/提醒/调试面板  │  │
│  └───────────┘   └──────┬───────┘   └────────────────────┘  │
│                          │                                   │
│         ┌────────────────┼────────────────┐                  │
│         ▼                ▼                ▼                  │
│   ┌───────────┐   ┌───────────┐   ┌──────────────┐          │
│   │ LLM Service│   │ ToolRegistry│  │ MCP ToolBridge│         │
│   │ (千问/VL)  │   │ 10+ 内置工具 │  │ 远程工具动态注册│         │
│   └─────┬─────┘   └─────┬─────┘   └──────┬───────┘          │
│         │               │                │                   │
│         └───────────────┼────────────────┘                   │
│                         ▼                                    │
│              ┌─────────────────────┐                         │
│              │   长任务 Agent 层     │                         │
│              │  ┌───────┐ ┌──────┐ │                         │
│              │  │Travel │ │ Plan │ │                         │
│              │  │Agent  │ │Agent │ │                         │
│              │  └───┬───┘ └──────┘ │                         │
│              └──────┼──────────────┘                         │
│         ┌───────────┼───────────┐                            │
│         ▼           ▼           ▼                            │
│   ┌──────────┐ ┌────────┐ ┌──────────┐                       │
│   │ 高德地图   │ │ 美团API │ │ RAG知识库 │                      │
│   │ JS API    │ │酒店/美食│ │ 关键词检索 │                      │
│   └──────────┘ └────────┘ └──────────┘                       │
└─────────────────────────────────────────────────────────────┘
```

---

## 核心功能

| 模块 | 能力 | 技术实现 |
|------|------|----------|
| **多用户会话** | 多人同时扫码登录，H2 持久化，重启免扫码 | wechat-ilink-sdk + H2 + 共享线程池 |
| **LLM 对话** | 文本/图片/语音多模态，edge-tts 语音回复 | 阿里云百炼 (千问) + OpenAI 兼容接口 |
| **工具系统** | 天气、待办、搜索、翻译、热点等 10+ 工具，LLM 自动调用 | Function Calling + ToolRegistry 动态注册 |
| **MCP 协议** | 接入远程 MCP Server，工具自动注册为本地可调用 | 官方 Java MCP SDK，支持 stdio / SSE / HTTP |
| **Skill 路由** | 关键词命中技能 → RAG 增强 → LLM 兜底，三级路由 | Skill 接口 + SkillRegistry 自动收集 |
| **RAG 检索** | 知识库关键词倒排索引，注入系统提示词 | 中英文分词 + 倒排索引 + Top-K |
| **定时提醒** | 自然语言建提醒（"明早 9 点开会"），主动推送到微信 | ReminderTimeParser + Cron + H2 持久化 |
| **旅行 Agent** | 一句话生成完整行程网页：景点、路线、酒店美食、语音 | 长任务拆解 + 高德地图 + 美团 API + edge-tts |
| **可交互地图** | 可缩放/拖动/点击标记/按天分色步行路线 | 高德 JS API 2.0 + 服务端 QPS 限流重试 |

---

## 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 / 框架 | Java / Spring Boot | 21 / 4.1.0 |
| 微信 SDK | wechat-ilink-sdk | 2.3.3 |
| LLM | 阿里云百炼（千问 qwen-plus / qwen-vl-plus） | OpenAI 兼容 |
| MCP | io.modelcontextprotocol.sdk:mcp | 2.0.1 |
| 地图 | 高德 Web 服务 REST + JS API 2.0 | — |
| 语音 | edge-tts（合成）/ 百炼 ASR（转写） | — |
| 二维码 | Google ZXing | 3.5.3 |
| 数据库 | H2（会话/提醒持久化） | 内置 |
| 测试 | JUnit 5 + Spring Boot Test | 108 个测试 |

---

## 快速开始

### 1. 环境要求

- JDK 21+
- Maven 3.9+
- 阿里云百炼 API Key（[获取地址](https://bailian.console.aliyun.com/)）
- 高德 Web 服务 Key（可选，用于行程地图）

### 2. 配置

复制 `application-local.properties` 模板，填入你的密钥（该文件已被 gitignore）：

```properties
# 阿里云百炼（必填）
llm.api-key=sk-your-dashscope-key

# 高德地图（可选，不填则跳过地图功能）
amap.rest-key=your-amap-rest-key
amap.js-key=your-amap-js-key
amap.security-js-code=your-security-code
```

### 3. 启动

```bash
mvn spring-boot:run
```

启动后打开 `http://localhost:8080`，扫码登录微信即可对话。

---

## 旅行规划 Agent（亮点功能）

用户只需一句话，Agent 自动完成完整链路并产出网页成品：

```
用户: 帮我规划杭州 2 日游

Agent 自动执行:
  1. 解析目的地/天数/预算/日期 → 缺失信息补问
  2. 查询目的地天气
  3. 联网搜索交通/景点/住宿/美食
  4. 检索本地旅行知识库 (RAG)
  5. LLM 生成结构化 JSON 行程
  6. 渲染交互式行程网页 (含地图/酒店/美食卡片)
  7. 写入每日待办
  8. 生成封面图 + 语音摘要 (失败自动跳过)

输出: http://localhost:8080/api/trips/{id}.html
```

行程网页包含：
- **可交互地图**：高德 JS API 2.0，支持缩放/拖动/点击标记弹窗/按天分色步行路线
- **酒店美食推荐**：美团开放平台真实数据，卡片式展示
- **每日行程**：景点介绍 + 交通方式 + 时间安排
- **语音摘要**：edge-tts 合成 MP3，可在线播放
- **封面图**：AI 生成行程主题图

---

## 项目结构

```
src/main/java/com/example/group_demo/
├── GroupDemoApplication.java          # 启动类
├── bot/                               # 微信消息收发与会话管理
├── controller/                        # REST API (BotController / TravelPageController)
├── llm/                               # LLM 服务 + 对话记忆 (长时摘要/TTL)
├── tool/                              # 10+ 内置工具 + 工具链编排
│   └── chain/                         # 确定性多步工具链
├── mcp/                               # MCP 协议接入 (Server/Bridge/Manager)
├── skill/                             # Skill 路由与技能实现
│   ├── travel/                        # 旅行规划技能
│   └── plan/                          # 周计划技能
├── router/                            # 三级消息路由 (Skill→RAG→LLM)
├── rag/                               # 关键词检索增强
├── scheduler/                         # 定时提醒 + 每日推送
├── travel/                            # 旅行 Agent 全链路
│   ├── TravelAgentService.java        #   长任务编排
│   ├── TravelPageRenderer.java        #   网页渲染 (含交互地图)
│   └── TravelJsonParser.java          #   LLM JSON 解析
├── amap/                              # 高德地图 (地理编码/路线/交互地图)
├── meituan/                           # 美团酒店/美食推荐
├── session/                           # 多用户会话 + H2 持久化
├── voice/                             # edge-tts 语音合成
├── image/                             # AI 封面图生成
├── weather/                           # 天气查询
├── news/                              # 热点新闻
└── search/                            # 联网搜索
```

---

## API 速览

| 端点 | 方法 | 说明 |
|------|------|------|
| `/api/bot/session` | POST | 创建微信会话 |
| `/api/bot/session/{id}/qr.png` | GET | 获取登录二维码 |
| `/api/bot/travel-agent` | GET | 触发旅行 Agent |
| `/api/trips/{id}.html` | GET | 查看行程网页 |
| `/api/trips/{id}.mp3` | GET | 语音摘要 |
| `/api/bot/skills` | GET | 已注册技能列表 |
| `/api/bot/route` | GET | 测试消息路由 |
| `/api/bot/rag/search` | GET | RAG 检索测试 |
| `/api/bot/reminders` | GET/POST | 提醒管理 |
| `/api/bot/mcp/status` | GET | MCP 连接状态 |
| `/api/bot/tool-chains` | GET | 工具链列表 |

完整 API 见 `BotController` (`/api/bot/**`) 与 `TravelPageController` (`/api/trips/**`)。

---

## 测试

```bash
mvn test
```

108 个单元/集成测试覆盖全部核心模块：LLM 工具调用、对话记忆、消息路由、Skill 注册、RAG 检索、提醒时间解析、旅行 Agent、高德地图客户端、MCP 工具桥接、美团客户端等。

---

## 配置参考

所有密钥放在 `application-local.properties`（已 gitignore），其余配置项见 `application.properties`。关键配置：

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `llm.api-key` | — | 百炼 API Key（必填） |
| `llm.model` | qwen-plus | 文本模型 |
| `llm.vision-model` | qwen-vl-plus | 图片理解模型 |
| `amap.enabled` | true | 高德地图开关 |
| `amap.rest-key` | — | Web 服务 REST key |
| `amap.js-key` | — | Web 端 JS API key |
| `amap.security-js-code` | — | JS API 安全密钥 |
| `meituan.enabled` | true | 美团推荐开关 |
| `meituan.mock-enabled` | true | 无凭证时用示例数据 |
| `rag.enabled` | true | RAG 检索开关 |
| `rag.top-k` | 3 | 返回资料块数量 |
| `scheduler.enabled` | true | 定时任务开关 |
| `travel.page-base-url` | localhost:8080/api/trips | 行程网页链接前缀 |
| `mcp.enabled` | true | MCP 总开关 |

> **高德地图说明**：`rest-key`（Web 服务类型）用于服务端地理编码/路线规划/静态图；`js-key`（Web 端 JS API 类型）用于前端可交互地图，需在高德控制台单独申请并配置域名白名单。两者是不同类型的 key。

---

## 文档

- [MCP 协议接入说明](docs/mcp.md)

---

## 团队协作

- 分支策略：`main` 为主干，`feature/<成员>` 为个人开发分支
- 提交规范：`feat: / fix: / docs: / refactor:` 前缀
- 测试要求：新增功能需附带测试，`mvn test` 全绿方可合并
