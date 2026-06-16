# 用户权限管理 — 最终实施方案

> 状态:实施定稿(综合两版方案与多轮互审收敛结果)。
> 适用:syncLingo-plus 后端(Spring Boot 3.2.5 / Java 21,单实例)、前端(React/TS)、C# Teams Bot、speaker-service。
> 核心理念:**先止血 → 建权限 MVP → 重机制按规模/合规触发**。服务端永不信任客户端身份声明。

---

## 1. 现状基线(已核对代码)

| 项 | 现状 | 位置 |
|---|---|---|
| 令牌 | 新登录签发 HS256 标准 JWT(`sub/iat/exp/jti/tv`);旧 `si.{payload}.{sig}` 仅解析兼容至自然过期;无角色 claim 信任 | `util/JwtUtil.java` |
| 认证 | 手写 `OncePerRequestFilter`,验签后仅写 `authenticatedUserId`;`/api/admin/**`、`/bot-api/**`、`/ws/**` 在白名单 | `filter/JwtAuthFilter.java` |
| 请求上下文 | `AuthContext.currentUserId()` 读请求属性 | `util/AuthContext.java` |
| 角色字段 | `si_user.role` 存在,`UserMapper.insert` 写了 role,但注册逻辑不赋值→NULL,从不用于鉴权 | `entity/SiUser.java`、`mapper/UserMapper.java`、`service/AuthService.java` |
| 管理接口 | 共享密钥 `X-Admin-Secret` / `?secret=`,与用户体系脱节 | `controller/AdminController.java`、`config/AppAdminProperties.java` |
| 越权风险 | ≥12 个 Controller 直接信任前端 `userId`;部分 Controller 绕过 facade 直连 Service/Mapper | `MeetingController`(Facade+Service)、`CostController`(Mapper)、`AudioRecordController`(Service) 等 |
| 归属校验 | `InterpretationController.requireSelf` 与 `MeetingService.requireOwner` 在 authId 为 null 时**放行** | 对应文件 |
| Bot 代理 | 透明 catch-all,**零鉴权**,转发几乎全部请求头 | `controller/BotApiProxyController.java` |
| 自动摘要 | Java 后端**直连** C# Bot `:3978/api/meetings/notification`,**不经 /bot-api** | `integration/MeetingBotIntegration.java` |
| 公开活动会话 | 旧 `/api/interpretation/public/user/{userId}/active` 已返回 410;分享页改走不可枚举 `share_token` 解析 | `controller/InterpretationController.java`、`ShareTokenService`、前端 `resolveShareToken` |
| 公开延迟上报 | `/api/interpretation/public/latency` 匿名 POST | 同上 |
| ASR WebSocket | 受保护 ASR WS 仅接受一次性 `ticket`;握手后按绑定 actor 校验 owned session 与连接状态机 | `ws/JwtHandshakeInterceptor.java`、`ws/AsrWebSocketHandler.java` |
| 启动期 DDL | 约 34 个文件在 `@PostConstruct` 建表/改表 | 多个 Mapper/Service |
| 前端 | `si_token` 存 localStorage;7 处 `userId || '1'` 默认用户;几乎每个 API 显式带 userId | `api/client.ts`、`api/index.ts`、多个 View |
| 测试 | 后端 83 个均为 mock 单测(无 `@SpringBootTest`/MockMvc);前端无测试运行器 | — |
| Bot 双副本 | 仓库同时存在 `bot/CallingBotSample` 与 `external/.../CallingBotSample` | — |

> 本文中"约 34 处运行时 DDL""83 个测试""18 个 Controller""98 个接口"等统计为**编写时快照**;实施时应由脚本/启动期盘点**自动生成并刷新**(接口数、测试数、运行时 DDL 数),避免代码演进导致方案失真。

---

## 2. 设计原则与不可违反规则

**原则**
1. 先止血后建设:IDOR、Bot 匿名等真实漏洞优先,不被重机制拖延。
2. 服务端永不信任客户端的 userId/角色/权限/资源归属声明。
3. 能力按触发条件实施,不默认全做。

**不可违反**
- IDOR / 资源归属 / 服务身份校验**永远 ENFORCE**,无报告模式。
- `/api/**` 默认需认证;仅显式标记 PUBLIC/SERVICE 例外;运行时默认拒绝在接口分类完成并验证后**逐域启用**。
- 授权顺序:`未认证→401` · `缺功能权限→403(不查资源)` · `有权限但资源越权/不存在→404(防枚举)` · `通过→执行`。
- 角色门禁:**默认 ENFORCE**;REPORT_ONLY 仅作为显式灰度/回滚开关。

---

## 3. 身份与凭证体系(三分离)

| 身份 | 凭证 | 强制点 |
|---|---|---|
| 人员用户(ADMIN/OPERATOR/VIEWER) | 标准 JWT + Refresh(HttpOnly Cookie) 已落 | 认证 Filter + `@RequirePermission` + 策略服务 |
| 浏览器→Java→C# Bot | 下行 HMAC 服务签名(密钥 A) | 显式 Bot Facade |
| C# Bot→Java `/api/teams-bot/**` | 上行 HMAC 服务签名(密钥 B) | 服务身份校验 |
| 运维 `/api/internal/ops/**` | 运维凭证 + 网段限制 | INTERNAL_OPS |
| 匿名分享听众 | 单会议/频道 Capability → 换 ws-ticket | HTTP 与 WS 同源校验 |
| WebSocket 握手 | 一次性 ws-ticket(P2+) | 握手绑 actor+session |

服务密钥**方向独立、不复用、可轮换**。`/api/messages` 平台回调继续走 Bot Framework 认证。

---

## 4. 角色 / 权限码 / 数据范围

### 4.1 角色
- **ADMIN**:账号、角色、审计、系统运维、全局元数据。默认**不能读 L2/L3 会议内容**。
- **OPERATOR**:管理自有或被授 OPERATE 的会议(准备、同传、摘要、通知)。
- **VIEWER**:只读查看被授 VIEW 的会议。

业务代码**只判权限码,不判角色大小**。角色→权限的映射在服务端集中维护。

### 4.2 权限矩阵
| 权限码 | ADMIN | OPERATOR | VIEWER | 范围 |
|---|:--:|:--:|:--:|---|
| USER_MANAGE / ROLE_ASSIGN / SESSION_REVOKE / AUDIT_READ | ✓ | ✗ | ✗ | ALL |
| OPS_EXECUTE | ✓ | ✗ | ✗ | ALL |
| COST_READ_ALL | ✓ | ✗ | ✗ | ALL |
| COST_READ_SELF | ✓ | ✓ | ✗ | OWN |
| DIRECTORY_READ(字段脱敏) | ✓ | ✓ | ✗ | — |
| DIRECTORY_MANAGE | ✓ | ✗ | ✗ | ALL |
| MEETING_METADATA_READ | ✓ | OWN+ASSIGNED | ASSIGNED | per-perm |
| ATTENDANCE_READ | ✓ | OWN+ASSIGNED | ASSIGNED | per-perm |
| MEETING_CONTENT_READ(转写/摘要/记录/资料) | **授权后** | OWN+ASSIGNED | ASSIGNED | per-perm |
| AUDIO_READ(录音下载,L3) | **授权后** | OWN+ASSIGNED | ✗(VIEW 默认不含) | per-perm |
| MEETING_MANAGE | ✓ | OWN+ASSIGNED(OPERATE) | ✗ | per-perm |
| MEETING_DELETE_OWN | ✗ | OWN | ✗ | OWN |
| MEETING_DELETE_ANY(L3,重认证) | ✓ | ✗ | ✗ | ALL |
| INTERPRETATION_OPERATE(start/stop) | **需授权/紧急** | OWN+ASSIGNED(OPERATE) | ✗ | per-perm |
| SUMMARY_EDIT / ACTION_ITEM_MANAGE | **授权后** | OWN+ASSIGNED | ✗ | per-perm |
| TEAMS_SEND / BOT_OPERATE | ✓ | OWN+ASSIGNED | ✗ | per-perm |
| TERMINOLOGY_* / HOTWORD_* | ✓ | OWN | ✗ | OWN |
| 账号自助(改密/资料) | ✓ | ✓ | ✓ | SELF |

> "**授权后**" = ADMIN 必须持有效 `support_access_grant` 才能对该资源执行;无授权返回 403。

### 4.3 数据范围
- `OWN`:资源 `user_id` = 当前用户。
- `ASSIGNED_VIEW` / `ASSIGNED_OPERATE`:`meeting_member` 命中且级别足够。
- `GLOBAL_METADATA`:仅元数据,不含正文。
- 无 meetingId 的历史会话以 `interpretation_session.user_id` 兜底。
- **无主/无法解析归属的数据默认锁定**,仅迁移工具或受审计管理员修复。

### 4.4 ADMIN 内容硬规则(防绕过)
- ADMIN 默认读不到 L2/L3,**唯一通道是 `support_access_grant`**。
- ADMIN **不得通过 meeting_member 给自己或其他 ADMIN 授内容权限**;meeting_member 不接受 ADMIN 作为内容 grantee。
- `meeting_member`:owner 只能授 `VIEW`;`OPERATE` 须 **ADMIN 授予 + owner 同意**;OPERATE grantee 可同传操作+编辑内容,**不可删会议/再转授/管成员**。
- 删除含正文/录音的整场会议属 L3:`MEETING_DELETE_ANY` 需重认证 + 理由 + 强制审计。
- **ADMIN 跨用户实时会话控制**(P3+):ADMIN **不天然拥有**停止/控制他人正在进行的同传;`INTERPRETATION_OPERATE` 对非自有会话须经**单独权限或紧急授权(support grant)**并审计,避免管理员随意干预正式会议。

---

## 5. 数据敏感度分类

| 级别 | 内容 | 默认规则 |
|---|---|---|
| L0 元数据 | 标题/时间/状态/资源 ID | 按角色 + 范围 |
| L1 人员信息 | 姓名/邮箱/Microsoft ID | 字段脱敏;导出需额外权限 |
| L2 会议内容 | 转写/译文/摘要/资料/行动项 | 仅 owner/member;ADMIN 默认不可读 |
| L3 高敏 | 录音/日志/批量导出/凭证/删除整场会议 | 专用权限 + 重认证 + 强制审计 |

"二次确认" = 重认证或额外权限,**不是前端弹窗**。

---

## 6. 交付三层与触发条件

**A. 立即止血(P0–P0.5)**:关闭真实漏洞,不依赖 Spring Security / 标准 JWT / MFA / 动态授权。
**B. 权限 MVP(P0.8–P3)**:固定角色矩阵、服务端角色解析、用户管理、审计、管理员初始化。
**C. 规模/合规触发(P4–P6)**:

| 能力 | 触发条件 |
|---|---|
| 标准 JWT + Refresh 旋转 | 用户增多 / 需短 token / 多设备会话管理 |
| meeting_member + VIEWER 登录 | 启用系统内多人协作 |
| support_access_grant | 管理员确需受控查看正文 |
| Channel Token | 旧分享准备下线 |
| Redis(状态缓存 + 跨实例失效) | 后端多实例部署前 |
| 双管理员审批 | 管理员数量与流程支持时 |

---

## 7. 数据库变更(Flyway)

> 引入 Flyway 前先对生产库 **baseline**;脚本可分阶段编写验证,但**生产启用一次切换**;执行前备份 + 预演;失败走前向修复/备份恢复,不承诺破坏性回滚。

```sql
-- si_user 扩展(回填 NULL role 之后再加 NOT NULL)
ALTER TABLE si_user
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE/PENDING/DISABLED
  ADD COLUMN token_version INT NOT NULL DEFAULT 0,
  ADD COLUMN last_login_time DATETIME NULL,
  ADD COLUMN password_changed_at DATETIME NULL;
-- 回填后:
ALTER TABLE si_user MODIFY COLUMN role VARCHAR(16) NOT NULL;    -- 取值约束 ADMIN/OPERATOR/VIEWER

-- 登录限流(IP + 账号维度,非账号级硬锁)
CREATE TABLE login_attempt (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  key_type VARCHAR(16) NOT NULL,        -- IP / USER / IP_USER
  key_value VARCHAR(128) NOT NULL,
  failure_count INT NOT NULL DEFAULT 0,
  blocked_until DATETIME NULL,
  updated_at DATETIME NOT NULL,
  UNIQUE KEY uk_login_key (key_type, key_value)
);

-- 会话/Refresh(P2)
CREATE TABLE auth_session (
  sid VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  refresh_token_hash VARCHAR(128) NOT NULL,
  token_family_id VARCHAR(64) NOT NULL,
  absolute_expires_at DATETIME NOT NULL,   -- 普通 12h / 记住设备 30d
  idle_expires_at DATETIME NOT NULL,       -- 8h 空闲
  last_used_at DATETIME NULL,
  created_ip VARCHAR(64) NULL,
  user_agent VARCHAR(255) NULL,
  revoked_at DATETIME NULL,
  KEY idx_user (user_id), KEY idx_family (token_family_id)
);

-- 会议成员(P3)
CREATE TABLE meeting_member (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  meeting_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  access_level VARCHAR(16) NOT NULL,       -- VIEW / OPERATE
  assigned_by BIGINT NOT NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_member (meeting_id, user_id)
);

-- 管理员临时内容授权(P3)
CREATE TABLE support_access_grant (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  grantee_user_id BIGINT NOT NULL,
  resource_type VARCHAR(32) NOT NULL,
  resource_id VARCHAR(64) NOT NULL,
  permissions VARCHAR(255) NOT NULL,
  reason VARCHAR(512) NOT NULL,
  requested_by BIGINT NOT NULL,
  approved_by BIGINT NULL,
  expires_at DATETIME NOT NULL,            -- ≤ 2h
  revoked_at DATETIME NULL,
  create_time DATETIME NOT NULL
);

-- 分享令牌(P4)
CREATE TABLE share_token (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  token_hash VARCHAR(128) NOT NULL,
  kind VARCHAR(16) NOT NULL,               -- SESSION / CHANNEL
  session_id VARCHAR(64) NULL,
  owner_user_id BIGINT NULL,               -- CHANNEL 指向操作员
  expires_at DATETIME NULL,
  revoked_at DATETIME NULL,
  create_time DATETIME NOT NULL,
  UNIQUE KEY uk_token (token_hash)
);

-- 审计(事务内 outbox → 异步归档仅追加存储)
CREATE TABLE audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_type VARCHAR(16) NOT NULL,         -- USER / SERVICE / SYSTEM
  actor_id VARCHAR(64) NULL,
  role VARCHAR(16) NULL,
  permission VARCHAR(64) NULL,
  resource_type VARCHAR(32) NULL,
  resource_id VARCHAR(64) NULL,
  action VARCHAR(64) NOT NULL,
  result VARCHAR(16) NOT NULL,             -- ALLOW / DENY / ERROR
  request_id VARCHAR(64) NULL,
  ip VARCHAR(64) NULL,
  detail_json JSON NULL,                   -- 脱敏:无密码/token/密钥/正文
  create_time DATETIME NOT NULL
);
```

应用层强制审计表**只追加**(代码不发 UPDATE/DELETE);保留期:安全/权限审计 365 天,普通访问 180 天。

> **audit_log 与 Outbox 区分**:`audit_log` 是最终的仅追加审计存储;若用 Transactional Outbox 保证"业务与审计原子",需**单独的 outbox 表**带投递状态(`PENDING/SENT/FAILED`)、重试次数、完成时间,由异步投递器搬运到 `audit_log`/归档。二者不可混为一张表。

---

## 8. 关键机制(定稿)

**ResourceOwnershipPolicy(P0.5 最小版,P3 升级)**
- **策略层显式接收 actor,禁止隐式读 AuthContext**(AuthContext 在异步/服务调用可能为空)。actor 由各入口分别构造并传入:HTTP 从过滤器、WS 从握手绑定、异步从入队捕获、服务从服务身份。
- **校验方法返回已查出的资源**,避免"先查归属、再重查资源"的重复查询与检查后变更(TOCTOU)。

```
AuthenticatedActor build/from: HttpActorResolver / WsActorResolver / AsyncActor / ServiceActor
requireCurrentUser(actor)                         // actor==null → 401
requireSelf(actor, requestedUserId)               // 不一致 → 403
requireOwnedMeeting(actor, meetingId)   -> Meeting
requireOwnedSession(actor, sessionId)   -> Session
requireOwnedFile(actor, fileId)         -> File      // 内部反查 meetingId 校验
requireOwnedSummary(actor, summaryId)   -> Summary   // 内部反查 sessionId
requireOwnedActionItem(actor, id)       -> ActionItem
requireOwnedAudio(actor, audioRecordId) -> AudioRecord
```
子资源一律反查父会议/会话;**无主/无法解析归属→拒绝**。P3 同名方法升级为支持 meeting_member 的完整 `AccessPolicyService`(增加 `actor` 的 ASSIGNED 判断),**调用点签名不变**。

**授权强制所在层级(防绕过)**:Controller/WS 入口**只负责解析并构造 actor**;**资源授权(Policy)在 Facade 或资源 Service 强制执行**——因为存在绕过 Facade 直连 Service/Mapper 的入口(如 `CostController`→Mapper、`AudioRecordController`→Service),只在 Controller 校验会漏。绕过 Facade 的调用必须按 §9.0 清单逐一纳入并在其执行点加 Policy。

**JWT secret 启动校验(P0,IDOR 前置)**:生产启动时若 `JWT_SECRET` 为默认值 `change_this_secret_to_random_32_plus_chars`、空或弱(< 32 字节)→**拒绝启动**。否则攻击者可伪造任意用户 token,所有 IDOR/归属修复失效。`ADMIN_API_SECRET`/`TEAMS_BOT_API_SECRET` 同样做生产必填校验。

**MVP 鉴权现状**:已由现有 token 迁到标准 JWT 证明 userId,但仍**每请求按 userId 从库读 role/status/tokenVersion**——实现"停用/降级/改密即时生效"(单实例 MVP 每请求一次 DB,规模上来再加缓存)。

**撤权(P2+)**:Caffeine 缓存 role/status/tokenVersion(**事务提交后逐出**,防并发回填旧值)+ `userId→活动 WS 连接`注册表(撤权主动关连接)+ 长连接每 30s 重验;**不逐音频包查权限**;多实例前迁 Redis(状态缓存 + Pub/Sub 关连接),迁移前禁止扩容。

**撤权后在途任务三分类**:摘要/Embedding 入队已授权→允许跑完;ASR/实时 TTS/Bot 发送→立即停止/拒绝后续;下载/通知→执行前再授权。

**WebSocket(明确启动顺序与状态机,不靠客户端裸传 sessionId)**
- **会话必须先由已鉴权的 HTTP 接口创建**(`/api/interpretation/start` 返回服务端生成的 sessionId 并记 owner);WS 只能**绑定到一个由当前 actor 拥有的、已存在的 session**。
- **P0.5c 立即修复 token 泄漏与来源**:`JwtHandshakeInterceptor` 当前 4 处 `log...uri=request.getURI()` 会把 `?token=` 写进日志(日志可下载)→**停止记录完整 URI 或脱敏**;`WebSocketConfig` 3 处 `setAllowedOrigins("*")` → **受保护 ASR WS 收紧为允许来源白名单**。
- **P0.5c 状态机(必须定义并测试)**:未 `start` 先 `audio/stop`→拒绝;重复 `start`→拒绝/幂等;同一连接切换 sessionId→拒绝并关闭;两个连接同时控制同一 session→拒绝后者;`translate_text` 是否要求已绑定会话需明确并测试。
- P0.5c 鉴权:握手解析 userId 构造 WsActor;`start` 用 `requireOwnedSession(actor, sessionId)` 校验(会话不存在或非本人→拒绝并关闭);连接绑定 sessionId;`audio/stop` 仅操作已绑定会话。
- **"撤权立即关连接"移到 P1/P2**(依赖角色状态 + `userId→活动连接`注册表;P0.5 尚无,不在此实现)。
- P2(start-ticket):受保护 HTTP 接口签发一次性 ticket(TTL 60s,一次性消费,绑 `userId+sessionId+权限`),握手只认 ticket,**Header/安全 Cookie 传递,绝不进 query**;每次连接/重连前重新申请。

**服务 HMAC 签名**
```
sig = HMAC(secret_by_keyId,
  keyId + "\n" + timestamp + "\n" + nonce + "\n" +
  method + "\n" + canonicalPath + "\n" + canonicalQuery + "\n" + sha256(body))
```
校验:TLS;时钟偏移 ≤ ±5min;nonce 窗口内去重;keyId 决定密钥与方向;来源网段白名单。

**登录限流**:`login_attempt`(IP、账号、IP+账号 多维)+ 逐步退避 + 阈值后验证码;**不做账号级硬锁**(避免定向 DoS)。

**会话(P2)**:`auth_session` 创建时定 `absolute_expires_at`(普通 12h / 记住设备 30d)+ `idle_expires_at`(8h);Refresh 旋转;**保留轮换历史/令牌族状态**(每次旋转记 `rotated_from` + 族当前有效 token 标识)以**可靠检测旧 token 重放**——旧 Refresh 重用→撤销整族;改密/停用/角色变更/登出→撤销;Cookie HttpOnly+Secure,**默认 SameSite=Strict**(分域/Teams Tab 需要时降 Lax + 强 CSRF);`/auth/refresh` 校验 Origin + CSRF + 限流。Access Token 仅存前端内存。

**审计可用性分级**
| 操作 | 审计失败 |
|---|---|
| 权限变更/管理员创建/Break-glass/L3 下载/Bot 控制/删除 | 操作失败关闭 |
| 普通资源读取 | 告警,可继续 |
| 实时同传音频链路 | 绝不中断会议 |

事务内写 outbox 审计表(与业务同事务保原子),异步归档到仅追加存储。

**System Principal**:每类后台任务签发固定最小权限主体(如"摘要任务"=仅该 session 的 `SUMMARY_*`),绑定 resourceId,不可跨资源,杜绝超级身份后门。

**首管理员 / Break-glass(离线 CLI)**
- 首管理员:仅当无 ACTIVE ADMIN 时执行;密码从 stdin;幂等(重复执行拒绝并记日志);**无 HTTP bootstrap 入口**。
- Break-glass:服务器本地 CLI;须工单号 + 理由;签发 ≤2h 指定资源/权限授权;**不提供全库正文访问**;写 DB 审计 + 服务器独立安全日志;事后通知所有者;网页不可触发。

**最后管理员保护**:guard row + 事务锁(`SELECT ... FOR UPDATE`)或角色变更服务串行化;并发降级被阻塞,不会双双通过。

**前端鉴权(P2)**:Access Token 内存;应用启动静默刷新 + 认证门控(刷新完成前挂起业务请求);401 单次刷新 + 合并并发刷新 + 成功重试 + 失败才登出;不信 localStorage.role(仅 UI);登录后 `/api/auth/me` 取权限;403 无权页、404 不透露归属;删除 7 处默认 userId=1 与散落 localStorage 读取。

**Flyway 切换**:盘点真实结构 → baseline → 启动期 DDL 转版本化 → 同一发布版关闭全部运行时建表/改表;P0.8 迁移**不碰运行时 DDL 管的表**,P0.8→P1 间禁新增运行时 DDL,P1 单次完成剩余迁移并关停。

**单一 Bot 源**:`bot/CallingBotSample` 为唯一生产源;`external/**` 仅参考;CI 禁止生产构建/部署引用 external。

---

## 9. 接口处置规则

### 9.0 接口授权清单(必备交付物)
P0 产出并持续维护一份**接口授权清单**,每个入口明确:`身份类型(用户/服务/匿名)` · `所需权限码` · `资源归属解析路径` · `401/403/404 返回规则` · `对应自动化测试`。**三类入口分别盘点**:Spring MVC(`RequestMappingHandlerMapping`)、WebSocket 注册路径(`WebSocketConfig`)、`/bot-api/**` catch-all(单独逐操作列举,不可作为整体放行)。清单作为默认拒绝与 CI 校验的依据。

### 9.1 分类处置
1. **本人资源 userId 过渡规则**(消除"忽略 vs 403"矛盾):**P0.5 保留参数兼容前端** → 缺省用 `actor.userId` → **参数与 actor 不一致→403 + 安全日志** → **下游永远使用 `actor.userId`(从不使用入参)** → **P5 删除参数**。涵盖会议列表/创建、会话列表、术语、热词、语言偏好、音频、本人费用、PreMeeting、跨会议问答。
2. **子资源**:用 `requireOwnedX(actor, id)` 反查父资源并返回资源;修复 `requireSelf/requireOwner` 空上下文放行(actor 为空→拒绝)。
3. **全局管理**:用户管理、全部费用、人员目录写、日志、重建任务 → ADMIN/OPS;人员目录读 → OPERATOR+(字段脱敏)。
4. **公开分享**:`/public/user/{userId}/active` 过渡保留(限流+不展示新链接),P4 频道令牌替代,P5 返回 410;`/public/latency`:**P0.5e = 请求体大小限制 + 字段白名单 + 限流(仍匿名,登记残余风险)**;**P4 = 改带 Share Capability**。
   - **限流规则**:键 = **可信代理解析后的 IP + 目标 userId**;**不直接信任客户端 `X-Forwarded-For`**(仅信任已配置可信代理跳数);定义阈值/时间窗口;超限返回 **429**。
5. **ASR WebSocket**:见 §8(会话先由已鉴权 HTTP 创建,WS 仅绑定本人会话)。
6. **Teams Bot 代理(`/bot-api/**`,P0.5 不止"限账号")**:同时满足——
   - **允许访问的用户配置**(`BOT_OPERATOR_USER_IDS`),**配置为空时拒绝**(默认关闭,不默认放行);
   - **转发路径 + HTTP 方法白名单**(只放行明确的 join/participants/summary 等操作);
   - 转发前**剥离** `Authorization`、`Cookie`、`Proxy-Authorization`、管理/服务密钥等敏感头(防外泄与伪造);
   - **禁止客户端控制转发目标地址与查询参数**(目标固定为配置的 C# Bot)。
   - **P0.5d 只做 Java 侧**(登录 + 用户/路径/方法白名单 + 剥头);**服务身份认证(Java 签名 + C# 校验)统一放 P4**——P0.5 不注入需 C# 校验的身份,以免 C# 一旦开始校验就中断自动摘要直连链路。
   - P4:改显式 Bot Facade + 会议归属 + 双向 HMAC。
7. **服务接口过滤器顺序**:`/api/teams-bot/**` 等**先验服务凭证,成功才绕过用户 JWT**;**不能简单加入公开白名单**,也**不能要求 C# Bot 携带用户 JWT**。`/api/messages` 走平台认证。
8. **管理/运维与日志下载**:浏览器用 ADMIN JWT;`/api/internal/ops/**` 用运维凭证(P4 拆分);**日志下载去 `?secret=`**——因浏览器地址栏无法附自定义 Header,**P0.5 阶段改用管理员 CLI 下载**(受认证管理页要到 P1a 才有),确保日志功能不因此不可用;迁移期共享密钥轮换 + 网段限制。
9. **HTTP 状态码兼容改动**:本次将"越权/无权"统一为 **403/404**。需**列出受影响接口清单**(部分接口此前以 HTTP 200 返回业务失败、部分脚本期望 401),并**同步修改前端处理与服务器验证脚本**,避免回归误判。

---

## 10. 阶段计划

| 阶段 | 内容 | 出口 |
|---|---|---|
| **P0(P0.5a 的前置,不可跳过)** | ①四类入口清单(HTTP via `RequestMappingHandlerMapping` / WS / C# Bot / 定时·异步,`@WebMvcTest`+MockBean 校验,非正则)②**现有行为基线**(关键接口当前返回码/归属行为快照,用于判回归)③最小安全回归测试套件 ④**JWT secret 启动校验**(默认值 `change_this_secret_to_random_32_plus_chars`/空/弱密钥→拒绝启动)⑤**建立 `application-test.yml` + test profile**(禁运行时 DDL/外部服务/生产库,生产库名或地址→fail-fast;单元/集成分组命令)⑥单一 Bot 源守卫;**不开运行时默认拒绝** | 清单完整;默认/弱 JWT secret 无法启动生产;test profile 可用;基线已留存 |
| **P0.5(本周必发,分批,依赖 P0 完成)** | 见下方批次拆分 a–e | A 改任何 userId/资源 ID 不能碰 B;WS 不能操作他人会话;Bot 拒匿名且不外泄 JWT;注册关闭;正式会议/自动摘要无回归 |
| **P0.8** | Flyway baseline + 先回填 NULL role 后加 NOT NULL + 仅权限表(不碰运行时 DDL 表) | 权限表就绪 |
| **P1a(角色+用户管理 MVP)** | 账号状态机 + ADMIN/OPERATOR 矩阵 + 每请求服务端角色/状态 + `@RequirePermission` + 用户管理 + 高危门禁即强制 + 管理页网络边界/IP 白名单 | 角色/停用即时生效;管理页非白名单不可达 |
| **P1b(默认拒绝+审计+迁移收尾)** | SERVICE/INTERNAL_OPS 临时认证适配 → 逐域开默认拒绝 + 审计(真正 Outbox)+ 运行时 DDL 单次迁完关停 | 默认拒绝不切链路;审计可靠投递 |
| **P2** | 标准 JWT + auth_session + Refresh 旋转 + WS Ticket + 强制重登 + CORS/CSRF + login_attempt 限流 | 令牌生命周期/长会议/撤权过 |
| **P3** | meeting_member(防 ADMIN 绕过)+ 启用 VIEWER 登录 + 完整 AccessPolicy + support_access_grant + 最后管理员保护 + `MEETING_DELETE_ANY`(首管理员 bootstrap 已在 P1a 前完成) | OWN/ASSIGNED/OTHER 矩阵过;ADMIN 内容需授权可证 |
| **P4** | 显式 Bot API + 会议归属 + 双向服务 HMAC + Channel/单会议 Capability(与旧接口限流并存)+ 运维拆 `/api/internal/ops` | 新分享可用;服务边界收口 |
| **P5(Sunset)** | 旧分享 **410** + 删兼容 userId/旧认证 + 低危门禁 ENFORCE + 完整安全边界验收 | 无旧入口、无未分类 |

### P0.5 批次拆分(各批独立发布、独立回滚)
> 职责边界(消除 a/b 冲突):**P0.5a 只把旧方法改 fail-closed,不引入新 Policy;P0.5b 才引入显式 actor 的 `ResourceOwnershipPolicy` 并迁移子资源接口。** 测试同步:`ResourceOwnershipPolicyTest` 属 P0.5b。

| 批次 | 内容 | 覆盖接口 | 独立验收 |
|---|---|---|---|
| **P0.5a** | ①修两处空上下文放行(`InterpretationController.requireSelf`、`MeetingService.requireOwner` actor 为空→**拒绝**)②对**当前裸信 userId、尚无任何归属校验**的本人接口加 mismatch→403 并改用 actor.userId | 会议列表/创建、会话列表、本人费用、术语、热词、语言偏好、音频本人操作(经核对:这些控制器**当前 0 处** requireSelf/AuthContext) | actor 为空不放行;改 userId 不能访问他人本人资源 |
| **P0.5b** | 引入显式 actor 的最小 `ResourceOwnershipPolicy`(`requireOwnedX(actor,id)->资源`),迁移子资源接口归属反查 | start / stop / status / history / saveResult / records / 会议文件内容与下载 / summary / 发言摘要 / actionItem / speaker mapping / 录音下载 | 改任意资源 ID→404 |
| **P0.5c** | ASR WebSocket 状态机 + 会话绑定(见 §8 WS) | `/ws/asr` start/audio/stop/translate_text;停止记 token 日志、收紧来源 | WS 不能操作他人/未绑定会话;日志无 token;来源受限 |
| **P0.5d** | Bot 代理:**仅 Java 侧**登录 + 用户白名单(空=拒绝)+ 路径/方法白名单 + 敏感头剥离 + 固定转发目标;**服务身份认证(C# 校验)留到 P4** | `/bot-api/**` | 匿名拒绝;JWT 不外泄;不可篡改目标;自动摘要直连不受影响 |
| **P0.5e** | 关闭注册(预检至少一个可登录管理/操作账号;定 register 关闭返回码 403/404/410;前端入口同删;锁死回滚)+ 日志下载改 CLI/认证页 + 公开会话(IP+目标 userId)限流 + **`/public/latency` 加请求体大小限制 + 字段白名单 + 限流(仍匿名,登记残余风险)** | `/api/auth/register`、日志下载、`/public/user/{userId}/active`、`/public/latency` | 注册关闭且不锁死;日志仍可下载;探测/灌日志被限流 |

> **每个接口在开工前必须明确归入 P0.5a 还是 P0.5b**(依据 §9.0 清单);不一次改全部安全边界,逐批发布、逐批灰度。

**回滚(不能简单回旧镜像就重新裸奔)**:P0.5 全程**不改数据库结构**;每批先备**临时 Nginx 阻断规则**——若应用必须回滚,**先在 Nginx 阻断该批涉及的高危接口**(如 `/bot-api/**`、相关资源接口),再回镜像,避免回滚瞬间重新敞开。归属/IDOR 校验**不允许"发现越权仍放行"的报告模式**(只能整批回滚)。

**阶段窗口说明**
- VIEWER 角色 P1a 定义但**不开放登录**,历史无法确认账号设 PENDING,P3 启用 meeting_member 后才放开。
- ADMIN 内容访问:support_access_grant(P3)前默认无网页正文通道,紧急走离线 Break-glass。
- 关闭注册在 P0.5e,**早于** P0.8 的 role NOT NULL。
- **首管理员初始化 + 历史 role 回填必须在 P1a 之前完成**(P1a 用户管理需要 ADMIN):P0.8 回填规则=按**用户名清单**指定首个 ADMIN(**禁止按 id=1 推断**),其余无法确认→VIEWER/PENDING;首管理员走离线 CLI(§8),不放到 P3。

---

## 11. 测试方案(贴合现有代码、不影响功能)

### 11.0 测试基建现状(决定测试形态)
| 维度 | 现状 | 影响 |
|---|---|---|
| 后端依赖 | `spring-boot-starter-test` 已在 pom(JUnit5 + Mockito + MockMvc + AssertJ + JSONassert 现成) | 单测/切片测试**无需新增依赖** |
| 后端风格 | 83 个全为纯 `@Test` + `mock()`/`when()` 单测,**无 `@SpringBootTest`** | 新测试优先沿用此风格 |
| 启动约束 | 约 34 处 `@PostConstruct` 执行 DDL → **完整上下文启动依赖 MySQL** | 鉴权测试**避开全量 `@SpringBootTest`**,改用切片 |
| 前端 | Vite 5,**无测试运行器** | 需引入 Vitest(复用 vite 配置) |
| 灰度工具 | admin 日志下载接口 + `tests/analyze_latency.py` 已有 | 直接用于真实流程回归 |

### 11.1 四种测试形态与选型(关键:不依赖 DB 也能覆盖鉴权)
1. **纯单测(首选,零新依赖、零 DB)**——沿用现有 `mock()` 风格。
   `ResourceOwnershipPolicy`、`AccessPolicyService`、权限映射、HMAC 校验、令牌解析、撤权分类等**纯逻辑**,用 `@Mock` 注入各 Mapper,断言抛出/放行。**P0.5 主力**。
2. **Web 切片(`@WebMvcTest` + `@Import(JwtAuthFilter)` + `@MockBean` service)**——只加载 Web 层与过滤器,**不加载 Mapper→不触发 `@PostConstruct` DDL→不需 MySQL**。验证:白名单放行、`/bot-api`/受保护接口需 JWT、401/403/404 顺序、跨用户→403/404。
3. **DB 集成(Testcontainers MySQL,test scope,仅此类用)**——归属反查、Flyway 空库/存量/回填迁移、`auth_session`/`meeting_member`/最后管理员并发锁。新增依赖**仅限 test scope**,不进运行时。
4. **前端(Vitest + @testing-library/react + jsdom)**——AuthProvider、路由/按钮守卫、401 静默刷新合并、403 无权页、localStorage.role 不提权。

### 11.2 "不影响功能"的硬保障(每阶段 gate)
- **现有测试必须持续全绿**,任一阶段提交前 `cd si-backend && mvn test` 通过。**不得删除或弱化既有测试**;当状态码契约变化(如 401/HTTP200 → 403/404)需修改旧用例时,**必须在提交说明记录契约变更原因,并为该用例补充对应安全断言**(不是简单改期望值)。
- **真实流程灰度核对清单**(每阶段部署后,沿用本项目既有手段):
  - 同传 `start/stop/status`、分享页持续收听、**自动摘要直连 C#(不经 /bot-api)**、Teams Bot 主动消息、长会议(>30min)、蓝牙播放。
  - 用 admin 日志接口拉新日志 + `analyze_latency.py` 比对,确认无新增 ERROR、无 `reuse connection`/`synth error`、无 `speakerName=null`、`recipientCount>0`。
- **默认拒绝逐域灰度**:P5 起 `app.authz.mode` 默认 ENFORCE;如部署验证发现误拦,仅可显式设置 `APP_AUTHZ_MODE=REPORT_ONLY` 临时回滚并保留告警日志。

### 11.3 分阶段测试用例(映射真实类)
| 阶段 | 新增测试(形态) | 回归 |
|---|---|---|
| **P0** | 接口分类完整性:`@WebMvcTest` 启动读 `RequestMappingHandlerMapping`,断言每个映射已分类;WS/C#/定时清单各自校验脚本 | 现有测试全绿 |
| **P0.5a** | 旧守卫 fail-closed 单测(`InterpretationController.requireSelf` / `MeetingService.requireOwner` actor 为空→拒绝);本人资源控制器切片:userId mismatch→403、缺省用 actor.userId(`ResourceOwnershipPolicyTest` 不在本批,属 P0.5b) | 本人资源灰度 |
| **P0.5b** | 归属反查单测(mock mapper):各 `requireOwnedX` 返回资源、子资源反查父、无主→拒绝;切片:改资源 ID→404 | 资源读写灰度 |
| **P0.5c** | `JwtHandshakeInterceptorTest` + AsrWebSocket 绑定单测:会话先 HTTP 创建、握手解析 userId、`start` 跨会话→拒绝、`audio/stop` 切 sessionId→拒绝 | 同传/分享灰度 |
| **P0.5d** | `BotApiProxyControllerTest`(切片+捕获 RestTemplate):空白名单→拒绝、非白名单路径/方法→拒绝、转发前 **Authorization/Cookie/密钥头已剥离**、转发目标不可篡改 | Teams Bot 灰度 |
| **P0.5e** | `JwtAuthFilterTest`(切片):`/bot-api` 去白名单需 JWT、公开路径仍放行;`AuthService` 注册关闭返回码;公开会话限流 + **伪造 `X-Forwarded-For` 不绕过限流** | 注册/日志/分享灰度 |
| **P0.8** | Flyway 迁移(Testcontainers):空库建表、存量升级不丢数据、NULL role 回填后再 NOT NULL、唯一约束 | 现有测试全绿 |
| **P1** | 权限矩阵参数化(切片):role×permission×{OWN/ASSIGNED/OTHER};停用/降级**下一请求即生效**(服务端状态加载);SERVICE/INTERNAL_OPS 临时适配后**现有链路不被默认拒绝切断**;审计 Outbox 与业务同事务 | 全流程灰度 + P5 后默认 ENFORCE |
| **P2** | 标准 JWT 生命周期、Refresh 旋转 + 重用→撤族、`absolute/idle` 过期、WS Ticket 一次性 + 重连重取、CORS/CSRF、`login_attempt` 多维限流;**长会议不因 Access Token 过期断连** | 长会议端到端回归 |
| **P3** | meeting_member OWN/ASSIGNED/OTHER 矩阵、**ADMIN 不能经 meeting_member 绕过 support_grant**、support_access_grant 申请/到期/撤销、最后管理员并发降级(Testcontainers 锁)、`MEETING_DELETE_ANY` 重认证 | 全矩阵回归 |
| **P4** | 显式 Bot API + 双向 HMAC(签名/重放/篡改)、Share/单会议令牌过期/撤销/篡改、令牌换 ws-ticket(非 query) | Bot/分享端到端 |
| **P5** | 旧分享 `410`、无未分类接口扫描、删兼容 userId 后回归 | 完整边界验收 |

### 11.4 新增测试依赖(最小化,均 test scope / devDependency)
- 后端:`org.testcontainers:junit-jupiter`、`org.testcontainers:mysql`(仅 §11.1-3 用)。
- 前端:`vitest`、`@testing-library/react`、`@testing-library/jest-dom`、`jsdom`;`package.json` 加 `"test": "vitest run"`,`vite.config.ts` 加 `test: { environment: 'jsdom' }`。
- **外部依赖(ASR/翻译/TTS/Teams)一律 Mock/Stub**,测试不打真实外部服务,不产生费用,不影响生产。

### 11.5 服务器测试约束(测试在 ECS 服务器执行)
测试在生产服务器上跑,服务器有真实 MySQL(`si-mysql`)且正在承载生产会议,因此:
- **强制 test profile**:测试一律以 `test` profile 运行,**禁用运行时 DDL、禁用外部服务、禁用生产数据库连接**;启动时**检测到生产库名 `si_backend` 或生产地址即立即终止测试**(fail-fast 守卫,防误连)。
- **禁止用生产部署脚本跑安全回归**:`scripts/deploy-and-test.sh` 会访问真实接口、可能创建/删除生产数据;安全回归用独立测试套件,目标指向测试环境或 Mock。
- **硬规则:测试绝不连生产库 `si_backend`、绝不用生产 DB 凭据。** DB 类测试只用 **Testcontainers 临时容器**(服务器已有 Docker,可用),或独立 `si_backend_test` 库;用例只对自建表读写,**不触碰生产表/数据**。
- **不在正式会议进行时跑重型/集成测试**;`mvn test` 在独立工作副本/目录构建,**与正在运行的 `si-backend` 容器互不影响**(容器跑的是已构建镜像,源码测试不重启它)。
- **纯单测(P0.5 主力)零 DB、零网络**,服务器任何时段可安全运行。
- Testcontainers 会拉起临时 MySQL 容器:确认服务器 Docker 可用、端口不冲突、用例结束**自动销毁**;CI 同此约束。
- 外部依赖(ASR/翻译/TTS/Teams/Cartesia)**全部 Mock**,测试不打真实外部、不产生费用。

### 11.6 基线命令(服务器)
```bash
# 前置(P0 交付):src/test/resources/application-test.yml(禁运行时 DDL/外部/生产库,生产库名→fail-fast)
# 单测/切片(无需 DB,任意时段安全;走 test profile)
cd /opt/syncLingo/si-backend && mvn -Dspring.profiles.active=test test
# DB 集成(Testcontainers 临时库;勿指向生产 si_backend;按 *IT 分组单独执行)
mvn -Dspring.profiles.active=test -Dtest='*IT' -DfailIfNoTests=false test
cd /opt/syncLingo/si-frontend && npm run build && npm test     # 引入 Vitest 后
cd /opt/syncLingo/bot/CallingBotSample && dotnet build CallingBotSample.csproj
cd /opt/syncLingo/speaker-service && .venv/bin/python -m pytest -q
# 灰度回归:admin 日志接口拉日志 → tests/analyze_latency.py 比对(非高峰时段)
```

---

## 12. 验收标准

- **P0.5(本周)**:改 userId/任意资源 ID 无法读/改/停/删他人资源;WS 不能操作他人会话;未登录不能调 `/bot-api/**`;注册关闭;正式同传/自动摘要/分享页无回归。
- **权限 MVP(P0–P3)**:VIEWER 无业务写;OPERATOR 不能管账号/运维;ADMIN 默认读不到 L2/L3、需授权;停用/降级/改密即时影响 HTTP 与 WS;管理/授权/下载/Bot 操作有脱敏审计。
- **完整边界(P4–P5)**:分享不可枚举/可撤销/可轮换;Bot/运维/分享/人员独立凭证体系;旧入口下线;无未分类接口。

---

## 13. 已登记的残余风险(明确接受)

1. MFA 已明确排除在 P6 及当前权限交付之外;管理访问依赖强密码、登录限流、账号状态/角色即时生效、审计、网络边界/IP 白名单兜底。
2. Refresh 已落 auth_session 轮换与重用撤族,但仍为单库/单域 MVP;跨域 Teams Tab 需重新评估 SameSite 与 CSRF 策略。
3. 单管理员部署无在线紧急正文访问;依赖离线 Break-glass。

---

## 14. 决策登记

| 决策项 | 结论 |
|---|---|
| 人员角色 | ADMIN / OPERATOR / VIEWER |
| 机器/分享身份 | 与 si_user.role 完全分离 |
| 公开注册 | 关闭(P0.5);保留则 PENDING |
| 功能权限不足 | 403 |
| 资源越权/不存在 | 404(防枚举) |
| IDOR | 永远 ENFORCE |
| RBAC 上线 | 默认 ENFORCE;REPORT_ONLY 仅作显式灰度/回滚 |
| ADMIN 数据范围 | 元数据可管;正文/录音需 support_access_grant |
| VIEWER | 经 meeting_member 显式授权 |
| MFA | 排除在 P6 及当前权限交付之外;未来若合规要求变化需另开方案 |
| 权限依据 | 服务端当前角色/状态,不信 JWT/前端 |

---

## P4-B 服务身份(Java ↔ C# Bot HMAC)落地说明

**已实现(代码 + 测试均通过:Java 全量,C# 7)**

签名规范(两端逐字节一致,跨运行时向量测试锁定值 `258QrV2Z2eWLck5cYzmmvFoEeAytfcd8htB2uwqpUi4=`):
```
canonical = keyId \n timestamp \n nonce \n METHOD \n path \n rawQuery \n hexSha256(body)
signature = Base64(HmacSHA256(secret, canonical))
头:X-Svc-Key-Id / X-Svc-Timestamp / X-Svc-Nonce / X-Svc-Signature
```
校验:时钟偏移 ≤ ±300s;nonce 进程内去重(TTL 600s)防重放;常量时间比对。

- **下行(Java→C#,keyId=`java-backend`,密钥 A)**:`BotProxyIntegration`(/bot-api 代理:summary/summary-file/summary/chat/join/participants)与 `MeetingBotIntegration`(直连 /api/meetings/notification)出站签名;C# `TeamsBotInboundSignatureMiddleware` 对 `/api/meetings` 验签。
- **上行(C#→Java,keyId=`csharp-bot`,密钥 B)**:C# `SyncLingoBotQueryService` 对 `/api/teams-bot/query[/stream]` 出站签名;Java `TeamsBotSignatureFilter` 验签。
- **附带修复**:`/api/teams-bot/**` 之前不在 `JwtAuthFilter` 放行清单 → 会被 "Missing token" 401 拦死(C# 仅发静态密钥、无 Bearer)。已加入放行,改由签名过滤器 + 静态 api-secret 保护。`TeamsBotQueryService` 静态密钥比对改为常量时间。

**强制策略**
1. 两端均**不配密钥** → 不验签(本地/未迁移环境继续靠静态 api-secret 兜底)。
2. 配置密钥后默认强制要求签名:Java 上行 `SERVICE_SIGNATURE_UPSTREAM_REQUIRED=true`;C# 下行 `RequireServiceSignatureDownstream=true`。
3. 如需迁移放行,必须显式设置 Java `SERVICE_SIGNATURE_UPSTREAM_REQUIRED=false` 或 C# `RequireServiceSignatureDownstream=false`;缺签名只在该显式迁移模式下放行并告警。

**部署配置**
- Java(env / backend.env):`SERVICE_SIGNATURE_DOWNSTREAM_KEY`、`SERVICE_SIGNATURE_UPSTREAM_KEY`(各 ≥32 随机字符,方向独立不复用)、`SERVICE_SIGNATURE_UPSTREAM_REQUIRED=true`。
- C# Bot(服务端 `appsettings.json` 的 `Bot` 段):`ServiceSignatureDownstreamKey`=密钥 A(=Java DOWNSTREAM)、`ServiceSignatureUpstreamKey`=密钥 B(=Java UPSTREAM)、`RequireServiceSignatureDownstream=true`。
- 两端 keyId 固定;将来多密钥轮换可按 keyId 选密钥(已预留接口形态)。

---

## P5 进度

**已实现:WS 一次性握手票据(消除长效 JWT 进 query)**
- 问题:鉴权 ASR WS 之前用 `/ws/asr?token={完整 JWT}`,JWT 会进 nginx/代理访问日志与浏览器历史。
- 后端:`WsTicketService`(进程内、60s、一次性、绑 userId)+ `WsTicketController` POST `/api/ws-tickets`(JWT 保护,签发本人票据)+ `JwtHandshakeInterceptor` 只消费 `ticket`,旧 `token` query 已拒绝。
- 前端:`mintWsTicket()` + `websocket.ts` 连接/重连前换取票据用 `?ticket=`,票据失败即终止连接,不再回退旧 token。
- 测试:`WsTicketServiceTest`(3)、`JwtHandshakeInterceptorTest`(票据一次性 + legacy token 拒绝)、`PermissionEntryPointCoverageTest` 仍过;Java/前端构建过。
- 残余:票据短暂出现在 query(单次、60s,泄露价值极低,远优于长效 JWT);多实例需迁 Redis。

**已实现:令牌版本 token_version(改密/停用即时失效)**
- 标准 JWT 负载加自定义 claim `tv`(tokenVersion);旧 `si.userId:username:expiresAt[:tokenVersion]` 令牌仅保留解析兼容窗口。
- `si_user.token_version`(启动期 DDL 补列 + schema.sql);登录按当前版本签发;`JwtAuthFilter` 加载用户后比对,不符→401 "Token superseded"。
- 改密 / 停用 → `incrementTokenVersion`,旧令牌即时作废(不止依赖状态判断)。DB 异常时降级跳过版本校验(沿用不锁人策略)。
- 测试:`JwtUtilTest`(标准 claims/版本往返/旧令牌兼容/过期/篡改)、`JwtAuthFilterRoleTest` 版本失效→401。前端无需改动(令牌不透明;受影响用户下次请求 401 重登)。

**已实现:标准 JWT(HS256, RFC 7519 claims)**
- 登录签发标准三段 JWT,header=`{"alg":"HS256","typ":"JWT"}`,payload 包含 `sub`(userId)、`name`、`iat`、`exp`、`jti`、`tv`。
- `JwtAuthFilter` 仍通过 `JwtUtil.verifyAndParseClaims` 校验签名/过期/claims,再按 userId 从库加载角色/状态/token_version;不信任前端或 JWT 内角色。
- 旧 `si.PAYLOAD.SIG` 令牌继续只读兼容,便于在线用户自然过期;新登录不再签发旧格式。
- 测试:`JwtUtilTest` 覆盖标准 JWT 结构、必需 claim 缺失、算法不符、过期、篡改、旧格式兼容;`AuthServiceTest` 覆盖登录返回标准 JWT。

**已实现:登录限流 LoginThrottleService(防暴力破解)**
- 多维 + 指数退避(进程内):**ip+账号**(阈值 5,主防护)、**ip**(阈值 20,跨账号撞库);超阈按 `5s·2^n` 退避、上限 15min、静默 15min 清零。
- **不做账号单维硬锁**(避免用错误密码定向锁死他人账号的 DoS);成功登录清该源对该账号计数。
- 接入 `AuthService.login(username, password, clientIp)`:登录前 `assertNotBlocked`(命中→429),失败 `onFailedAttempt`,成功 `onSuccessfulLogin`;IP 取服务端 `remoteAddr`(不信可伪造的 XFF)。
- 测试:`LoginThrottleServiceTest`(7)、`AuthServiceTest`(5:阻断短路/验证码短路/失败记账/停用拒绝/成功清零);Java 全绿。
- **部署要求**:反代后须配 `server.forward-headers-strategy`(或 nginx 传可信 `X-Real-IP`)使 remoteAddr 反映真实客户端,否则 ip 维退化为按代理 IP。前端 429 复用既有错误提示。

**已实现:阈值后验证码(配合登录限流)**
- 软阈值:ip+账号失败 ≥3 或 ip 失败 ≥10 时,登录需一次性验证码;硬阈值仍按原退避返回 429。
- 后端:`CaptchaChallengeService` 进程内签发 5 分钟数学题验证码,绑定 remoteAddr+username,验证成功即消费;`GET /api/auth/captcha` 公开签发,`POST /api/auth/login` 缺失/错误验证码返回 428。
- 前端:登录页遇到 428 自动拉取验证码并显示输入;提交时带 `captchaId/captchaAnswer`;用户名变化清空旧挑战。
- 测试:`CaptchaChallengeServiceTest`、`LoginThrottleServiceTest` 软阈值、`AuthServiceTest` 428 短路;前端 `tsc` 已过。

**已实现:auth_session + Refresh 轮换**
- 后端新增 `auth_session` 表与启动期初始化;登录写入 refresh token hash,原始 refresh 仅通过 HttpOnly `si_refresh_token` cookie 返回。
- `POST /api/auth/refresh`:校验 Origin(存在时必须同源)与 IP 频控,再校验 refresh hash、状态、absolute/idle 过期和账号状态;成功后旧行标记 `ROTATED`,插入同 family 新 refresh;旧 refresh 重用或已撤销/过期会撤销整族并返回 401。
- `POST /api/auth/logout`:按 refresh cookie 撤销整族并清 cookie。Cookie 为 `HttpOnly; SameSite=Strict; Path=/api/auth`;`Secure` 按当前请求是否 HTTPS 设置,便于本地 HTTP 验证。
- 前端 access token 改为内存存储;登录后不再写 `si_token` 到 localStorage;应用启动和 protected API 401 时通过 refresh cookie 换取新 access token,并只保留 `userId` 作启动门控。
- 测试:`AuthSessionServiceTest` 覆盖签发/轮换/重用撤族/过期撤族;`AuthControllerSecurityTest` 覆盖 cookie 写入/轮换/清理/跨 Origin 拒绝;前端 `tsc` 与 build 通过。

**已实现:账号自助安全(改密 + 全设备登出)**
- `AccountService` + `AccountController`(`/api/account/**`,JWT 保护,仅作用于本人):
  - `POST /api/account/password`:校验当前密码、新密码强度(≥6)、不得与原密码相同 → 更新 + 自增 token_version(旧令牌即时失效)+ 审计。
  - `POST /api/account/logout-all`:自增 token_version,作废本人所有已签发令牌 + 审计。
- 端点放 `/api/account/**` 而非 `/api/auth/**`(后者在 JWT 放行清单,无法取 actor)。
- 前端新增 `#/account-security` 账号安全页:修改密码、退出所有设备;退出所有设备成功后清本机内存 token/localStorage 并回登录页。
- 测试:`AccountServiceTest`(6:当前密码错/弱新密码/重复/成功作废令牌/未知用户/登出);Java 全量、前端类型检查/构建通过;本地浏览器已验证未登录访问账号安全页会回登录页且无控制台错误,已登录表单交互需在后端+数据库栈可用时联调。

**已实现:P5 Sunset 旧分享与旧 WS 认证**
- 旧公开活动会话 `/api/interpretation/public/user/{userId}/active` 已改为 `410 Gone`,不再解析目标 userId,避免按用户编号探测活动会话。
- 前端删除 `#/share/user/:userId` 路由与 `getActiveSessionForUser` helper,新分享仅生成/消费 `#/share/token/:token`。
- 旧 ASR WS 长效 JWT query 回退已删除,后端旧 `?token=` 握手拒绝;前端不再拼接 token。
- 测试覆盖:旧 active lookup 410、旧 WS token 拒绝、分享令牌解析仍保留。

**已实现:P5 低危门禁默认 ENFORCE**
- `AuthorizationProperties` 默认 `ENFORCE`,并在 `application.yml` 暴露 `APP_AUTHZ_MODE` 作为显式灰度/回滚开关。
- `AuthorizationEnforcementInterceptor` 在默认模式下对缺权限 USER 接口返回 403;`REPORT_ONLY` 仅记录 would-deny 并放行。
- `JwtAuthFilter` 仍按每请求加载服务端角色/状态/token_version;角色加载异常时不信任 token 内角色,默认 ENFORCE 下由权限拦截器 fail-closed。
- 测试覆盖:默认模式断言、REPORT_ONLY 不拦截、ENFORCE 越权/无角色拒绝、OPERATOR/ADMIN 放行、非 USER/未声明跳过。

**已实现:P5 删除本人资源 userId 兼容参数**
- 后端本人资源接口不再接收客户端 `userId`:会议列表/创建、同传会话列表/title/delete、翻译、术语、热词、语言偏好、音频、费用、PreMeeting、发言摘要均从 `AuthContext.requireActor().userId()` 派生。
- 前端 `src/api` 删除对应 `userId` 参数与 query/body 拼接;页面调用点同步改造,目标用户管理/成员授权等真实业务 userId 保留。
- 测试覆盖:`UserIdBoundaryControllerTest` 和 `InterpretationFacadeSecurityTest` 断言下游使用认证 actor;前端 `tsc` 防止旧调用签名回流。

**P5 后续验收**
- 部署前确认生产 `si_user.role` 回填完整;部署后观察 `Authz DENY/would-deny` 日志与正式会议关键流程。若发现误拦,临时设置 `APP_AUTHZ_MODE=REPORT_ONLY` 回滚观察。

**仍需生产窗口的数据库收口**
- Flyway/关闭运行时 DDL 不能在无生产 baseline 的普通发布中直接默认切换;当前代码仍保留历史启动期 DDL 以免未迁移库启动失败。
- 下一步需在生产维护窗口完成真实结构盘点、备份、Flyway baseline、空库/存量库预演,再关闭 `@PostConstruct` 建表/改表路径。
