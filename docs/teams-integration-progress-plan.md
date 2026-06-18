# Teams 集成当前状态

本文已从历史流水记录收敛为当前状态说明，避免旧 PoC 步骤误导生产部署。

## 当前保留能力

- C# Teams Bot 接收 Teams 个人聊天消息。
- C# Bot 调 Java `/api/teams-bot/query` 和 `/api/teams-bot/query/stream`，用于会议历史和会议文件问答。
- Java 后端提供 `/bot-api/api/meetings/summary` 代理，网页端发送 Teams 文本通知必须先经过 Java 用户权限校验。
- C# Bot `/api/meetings/summary` 和 `/api/meetings/notification` 只接受服务调用，并返回成功账号/失败账号明细。

## 已删除或废弃

- 前端 Teams Bot 独立页面。
- 会议链接填写、Bot 自动入会、退出会议、参会人员拉取。
- 会议聊天发送。
- SharePoint PDF 上传发送。
- 外部 `external/Microsoft-Teams-Samples/...` 生产源码路径。
- 本地隧道和任何 ngrok 生产依赖。

## 当前部署入口

- C# Bot 源码：`bot/CallingBotSample`
- Java Bot 代理：`/bot-api/**`
- Azure Bot Messaging endpoint：`https://<domain>/api/messages`
- Linux 部署：`docs/deployment-runbook.md`

## 验证重点

- Azure Bot 能通过生产域名触达 `/api/messages`。
- Teams 用户问答能返回会议来源。
- 网页发送通知后展示通知成功账号和通知失败账号。
- 未授权网页用户不能调用 `/bot-api/**`。
