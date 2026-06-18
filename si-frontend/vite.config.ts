import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import fs from 'node:fs'

// 可选 HTTPS：设了 VITE_HTTPS_KEY / VITE_HTTPS_CERT 且文件存在则启用（供局域网/手机收听音频用）。
// 不设则保持普通 http localhost 开发，零影响。
const httpsKey = process.env.VITE_HTTPS_KEY
const httpsCert = process.env.VITE_HTTPS_CERT
const httpsConfig =
  httpsKey && httpsCert && fs.existsSync(httpsKey) && fs.existsSync(httpsCert)
    ? { key: fs.readFileSync(httpsKey), cert: fs.readFileSync(httpsCert) }
    : undefined

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // 监听所有网卡 + 允许隧道/局域网域名访问，便于其他设备访问
    host: true,
    allowedHosts: true,
    https: httpsConfig,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        // 改写 Origin 为后端允许的来源，避免局域网/隧道访问时的 CORS 403
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.setHeader('origin', 'http://localhost:5173')
          })
        },
      },
      '/bot-api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true,
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
})
