# SI Frontend - 同声传译系统前端

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
│   │   ├── LoginView.tsx        # 登录页
│   │   ├── InterpretationView.tsx  # 同传主页面
│   │   └── VoiceCloneView.tsx   # 音色克隆页
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

- `/login` — 登录页
- `/` — 同声传译主界面
- `/voice-clone` — 音色克隆页
