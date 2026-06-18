# Teams Bot 安装与授权指南

本文只记录当前仍有效的 Teams Bot 配置。

## 1. Azure / Teams 配置

- Azure Bot App ID 与 Teams manifest `botId` 必须一致。
- Teams manifest `validDomains` 必须是生产域名，不带协议。
- Azure Bot Messaging endpoint 必须是 `https://<domain>/api/messages`。
- Bot `BackendBaseUrl` 必须指向 Java 后端内网地址，例如 `http://127.0.0.1:8080/`。

## 2. Graph 权限

用于向 Teams 用户发送个人消息时，需要按租户策略授予应用权限，并在 Teams Admin Center 发布应用到组织目录。实际权限以 Microsoft 当前后台为准，至少要覆盖：

- 读取用户并定位 Teams 账号。
- 为用户安装组织目录中的 Teams app。
- 使用 Bot Framework conversation reference 发送个人消息。

上线前必须由租户管理员完成授权并记录 CatalogAppId。

## 3. 生产配置

模板见：

- `bot/CallingBotSample/appsettings.example.json`
- `deploy/linux/env/appsettings.Production.example.json`

关键字段：

- `Bot:CatalogAppId`
- `Bot:BackendApiSecret`
- `Bot:ServiceSignatureDownstreamKey`
- `Bot:ServiceSignatureUpstreamKey`
- `AzureAd:TenantId`
- `AzureAd:ClientId`
- `AzureAd:ClientSecret`

## 4. 验证

- Teams 中给 Bot 发送普通问答消息，确认 C# Bot 能调用 Java `/api/teams-bot/query`。
- 在 syncLingo 历史记录/会议页点击发送通知，确认页面显示成功账号和失败账号。
- 未在 `BOT_API_ALLOWED_USER_IDS` 中的网页账号调用 `/bot-api/**` 应返回拒绝。
