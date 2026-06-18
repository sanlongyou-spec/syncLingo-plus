# 一键启动与部署口径

本文件保留为历史入口说明。当前口径如下：

## 本地开发

Windows 本地开发使用根目录：

```bat
start-all.bat
stop-all.bat
```

`start-all.bat` 仅启动本地测试栈：

- Java backend Docker container
- React frontend dev server
- speaker-service
- C# Teams Bot on `localhost:3978`

脚本不再启动 ngrok。真实 Teams 回调必须走生产域名。

## 生产部署

生产部署使用 Linux + Nginx + HTTPS。当前唯一部署手册：

- `docs/deployment-runbook.md`
- `docs/deployment-checklist-aliyun.md`
- `deploy/linux/`

不要再把客户端机器作为生产后端，也不要使用本地隧道承载商用服务。
