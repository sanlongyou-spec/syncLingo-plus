# Teams 与同传自动化当前边界

Teams Bot 当前保留两类能力：

- Teams 个人聊天中的会议历史/资料问答。
- 从 syncLingo 网页端向指定 Teams 账号发送会议通知或会议总结文本，并返回成功/失败账号列表。

当前不包含：

- Teams Bot 页面。
- 在网页端填写会议链接让 Bot 自动入会。
- 从 Teams 拉取参会人后自动建热词。
- SharePoint PDF 上传发送链路。
- 通过本地隧道暴露 Bot 服务。

生产入口：

- Azure Bot Messaging endpoint: `https://<domain>/api/messages`
- Browser `/bot-api/**`: 先进入 Java backend 做用户权限校验，再由 Java 用服务签名调用 C# Bot。
- Bot Framework `/api/messages`: 由 Nginx 直接转发到 C# Bot。

部署和验证以 `docs/deployment-runbook.md` 为准。
