# SI Frontend - 聚龙同传系统前端

## 技术栈

- React 18 + TypeScript
- Vite 5
- React Router 6
- Axios
- Zustand（状态管理）

## 项目结构

```
si-frontend/
├── src/
│   ├── api/                     # API 请求封装
│   │   ├── client.ts           # Axios 实例
│   │   └── index.ts            # 接口函数
│   ├── lib/
│   │   ├── websocket.ts         # WebSocket 客户端
│   │   └── audioCapture.ts      # 麦克风采集
│   ├── types/
│   │   └── index.ts             # TypeScript 类型
│   ├── views/
│   │   ├── LoginView.tsx           # 登录页
│   │   ├── InterpretationView.tsx  # 同传主页面（始终挂载，其余页面以浮层叠加）
│   │   ├── VoiceCloneView.tsx      # 音色克隆
│   │   ├── HistoryView.tsx         # 历史记录 + AI 会议纪要/发言摘要
│   │   ├── TerminologyView.tsx     # 术语表 / ASR 热词管理
│   │   ├── TeamsBotView.tsx        # Teams Bot 会前准备 / 会议集成 / 问答
│   │   ├── CostAnalysisView.tsx    # 成本分析
│   │   ├── ShareView.tsx           # 单会话分享页
│   │   └── UserShareView.tsx       # 用户永久分享页
│   ├── App.tsx
│   └── main.tsx
├── .env.example
├── vite.config.ts
└── tsconfig.json
```

## 快速开始

```bash
cd si-frontend
npm install
npm run dev
```

前端服务：`http://localhost:5173`

代理配置：API 请求代理到 `http://localhost:8080`，WebSocket 代理到 `ws://localhost:8080`。

## 页面说明

使用 HashRouter。登录页与分享页为独立路由，其余功能页在登录后以浮层（route-overlay）叠加在同传主界面之上：

- `/login` — 登录页
- `/` — 同传主界面
- `/voice-clone` — 音色克隆
- `/history` — 历史记录 + AI 会议纪要/发言摘要
- `/terminology` — 术语表 / ASR 热词管理
- `/teams-bot` — Teams Bot（会前准备 / 会议集成 / 问答）
- `/cost-analysis` — 成本分析
- `/share/:sessionId` — 单会话分享页（免登录）
- `/share/user/:userId` — 用户永久分享页（免登录）
