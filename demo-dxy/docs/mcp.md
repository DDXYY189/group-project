# MCP 协议与 Agent 能力接入

## 什么是 MCP

MCP（Model Context Protocol）是 Anthropic 在 2024 年提出的开放协议，用于在 AI 应用和外部工具/数据源之间建立标准化连接。

- Agent（客户端）通过 MCP 发现并调用外部工具
- MCP Server 提供工具、资源和提示词
- 传输方式支持 stdio（本地子进程）和 HTTP/SSE（远程服务）

本项目使用官方 Java MCP SDK `io.modelcontextprotocol.sdk:mcp:2.0.1`，把远程 MCP 工具适配为现有 `BotTool`，统一交给 LLM 工具调用。

## 接入方式

在 `application.properties` 中配置 `mcp.servers.*`，服务启动后会自动连接并把工具注册到 `ToolRegistry`：

```properties
mcp.enabled=true

# stdio 方式，需要本地安装 Node.js / npx
mcp.servers.everything.type=stdio
mcp.servers.everything.command=npx
mcp.servers.everything.args=-y,@modelcontextprotocol/server-everything

# HTTP/SSE 方式
# mcp.servers.remote.type=sse
# mcp.servers.remote.url=https://your-mcp-server.example.com
# mcp.servers.remote.headers.Authorization=Bearer xxx
```

支持的类型：

- `stdio`：启动本地命令，常见于 `npx` 安装的 MCP Server
- `sse`：HTTP + SSE 传输
- `http` / `streamable`：Streamable HTTP 传输

## 工具命名

远程工具会注册为 `mcp_<server>_<tool>`，例如 `demo-server` 的 `remote-time` 会变成 `mcp_demo_server_remote_time`，避免与本地工具重名，同时满足 LLM 函数名格式要求。

## 调试接口

```powershell
# 查看 MCP 服务状态和已接入工具
Invoke-RestMethod http://localhost:8080/api/bot/mcp/status

# 重新连接所有 MCP Server
Invoke-RestMethod -Method Post http://localhost:8080/api/bot/mcp/reload
```

## 新增能力

1. 找到可用的 MCP Server（官方或社区发布）
2. 在 `application.properties` 增加对应配置
3. 启动机器人，`ToolRegistry` 自动注册远程工具
4. LLM 对话中直接描述需求，模型会根据工具描述调用

## 注意事项

- stdio 服务依赖本机命令，Windows 上 `npx` 可能需要写完整路径或使用 `cmd /c`
- 远程服务需要网络可达，连接失败不会影响机器人启动
- 工具结果会以文本形式回传给 LLM；图片等二进制内容只返回长度信息
