# 第二阶段优化方案归档

本文件原记录同传系统第二阶段优化设想。商用发布整理后，实时产品边界已收敛，旧方案中的 Teams 自动入会、客户端多服务启动和实验性媒体桥接不再作为当前路线。

当前路线：

- 浏览器端负责同传操作和分享页播放。
- Java 后端负责 ASR、翻译、TTS、术语、AI 问答、权限和审计。
- speaker-service 负责声纹、标点/分段辅助和音色性别检测。
- C# Teams Bot 负责 Teams 问答和指定账号文本通知。
- Linux + Nginx + HTTPS 是唯一生产部署口径。

后续优化应在 `docs/optimization-implementation-plan.md` 和 `docs/optimization-validation-test-plan.md` 追加周记录，不再恢复旧二阶段 PoC 方案。
