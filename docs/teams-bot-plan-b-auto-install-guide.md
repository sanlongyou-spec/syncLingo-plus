# Teams Bot 方案 B：组织目录自动安装授权指南

本文记录 syncLingo Teams Bot 向用户私聊发送摘要、以及用户在 Teams 中和 Bot 查询会议记录时的正式授权路径。

## 1. 根因

Teams 平台要求 Bot 必须先安装到用户的个人应用范围，才能稳定向该用户发送 proactive 私聊消息。

旧的 `CreateConversationAsync` fallback 在用户未安装 Bot 时容易失败，最终表现为：

```text
No Teams users received the summary.
```

当前项目已删除该 fallback，正式采用方案 B：

```text
Teams App 发布到组织目录
  -> 配置 Bot:CatalogAppId
  -> Graph 自动安装 Bot 到用户 personal scope
  -> 获取用户 personal chat
  -> Bot Framework Connector 发送私聊摘要
```

## 2. Azure AD 应用权限

在 Azure Portal 中打开 Bot 对应的应用注册：

```text
ed32d429-d58f-4a4e-89dd-bfc4312aab4d
```

添加 Microsoft Graph 应用程序权限：

| 权限 | 用途 |
|---|---|
| `User.Read.All` | 通过邮箱、UPN 或 AAD ID 解析 Teams 用户资料 |
| `TeamsAppInstallation.ReadWriteSelfForUser.All` | 允许 Bot 将自己的 Teams App 自动安装到用户 personal scope |

添加后必须点击：

```text
代表租户授予管理员同意
```

## 3. 发布 Teams App 到组织目录

本项目根目录已有 Teams 应用包：

```text
syncLingo-teams-app.zip
```

发布步骤：

1. 打开 Teams 管理中心：`https://admin.teams.microsoft.com`
2. 进入 `Teams 应用` -> `管理应用`
3. 点击 `上传`，选择 `syncLingo-teams-app.zip`
4. 完成审核 / 允许该应用在组织中使用
5. 在应用详情页复制 Teams 应用的目录 ID，也就是 `CatalogAppId`

注意：`CatalogAppId` 不是 Azure AD Client ID。Teams App manifest 中的 `id` / `botId` 是：

```text
ed32d429-d58f-4a4e-89dd-bfc4312aab4d
```

上传到组织目录后，Teams 会分配目录中的 app catalog ID，发送链路需要使用这个 ID。

## 4. 查询 CatalogAppId

可以在 Teams 管理中心应用详情页复制，也可以用 Graph 查询：

```powershell
az login --tenant 49306cd1-4f6e-45ae-9eff-59a1bd8936d2
az rest --method GET --url "https://graph.microsoft.com/v1.0/appCatalogs/teamsApps?`$filter=externalId eq 'ed32d429-d58f-4a4e-89dd-bfc4312aab4d'"
```

返回结果里的 `id` 就是需要写入 `Bot:CatalogAppId` 的值。

## 5. 配置 C# Bot

编辑：

```text
external/Microsoft-Teams-Samples/samples/bot-calling-meeting/csharp/Source/CallingBotSample/appsettings.json
```

设置：

```json
{
  "Bot": {
    "CatalogAppId": "<Teams app catalog id>",
    "BackendBaseUrl": "http://localhost:8080"
  }
}
```

如果启用了 Java 查询接口共享密钥，还需要同时设置：

```json
{
  "Bot": {
    "BackendApiSecret": "<same secret as TEAMS_BOT_API_SECRET>"
  }
}
```

## 6. 验证

重新构建并启动 C# Bot 后，发送摘要时应看到类似日志：

```text
[ChatService] Installing bot app for user {Id}
[ChatService] App already installed for user {Id}
[ChatService] SendMessageToUser end
```

如果缺少权限或 CatalogAppId，接口会返回明确错误，例如：

```text
Bot:CatalogAppId is required for Teams user messages...
```

或 Graph 返回权限相关错误。优先检查：

1. `CatalogAppId` 是否是 Teams 组织目录中的 app id。
2. Azure AD 是否已授予 `TeamsAppInstallation.ReadWriteSelfForUser.All` 应用权限并管理员同意。
3. 目标用户是否在同一租户内，邮箱 / UPN / AAD ID 是否能被 Graph 解析。

## 7. 当前项目决策

- 不再使用参会人员手动安装作为默认路径。
- 不再使用 `CreateConversationAsync` fallback。
- 摘要发送和 Bot 查询能力都以 Teams 用户个人聊天为主。
- 真实生产多人环境后续建议增加独立 `teams_user_binding` 表，替代临时用 `si_user.email` / `username` 映射 Teams 用户。
