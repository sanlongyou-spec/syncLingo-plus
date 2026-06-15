-- ============================================================================
-- P1 角色回填(在切换 app.authz.mode=ENFORCE 之前,在服务器上手动执行)
-- ----------------------------------------------------------------------------
-- 背景:历史 si_user.role 默认 'user',不属于 ADMIN/OPERATOR/VIEWER,
--       服务端会解析为 null(无任何功能权限)。REPORT_ONLY 期不受影响,
--       但切 ENFORCE 前必须按真实人员清单回填,否则全员被拒。
-- 原则:① 首个管理员按"用户名"明确指定,禁止按 id=1 猜测;
--       ② 无法确认的账号回填为 VIEWER(最小权限),而非 OPERATOR;
--       ③ 执行前务必备份 si_user。
-- ============================================================================

-- 0) 确保 status 列存在(应用启动已自动加;手动环境可执行,已存在会报 Duplicate column,忽略即可)
-- ALTER TABLE si_user ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

-- 1) 备份(强烈建议)
-- CREATE TABLE si_user_backup_p1 AS SELECT * FROM si_user;

-- 2) 指定管理员(把下面的用户名换成真实管理员账号;可多行)
UPDATE si_user SET role = 'ADMIN',  status = 'ACTIVE' WHERE username = 'REPLACE_WITH_ADMIN_USERNAME';

-- 3) 指定操作员(秘书处/译员等实际操作账号;按需逐个列出)
-- UPDATE si_user SET role = 'OPERATOR', status = 'ACTIVE' WHERE username IN ('op1','op2');

-- 4) 其余无法确认的历史账号 → 最小权限 VIEWER(只读)
UPDATE si_user
SET role = 'VIEWER'
WHERE role IS NULL OR role NOT IN ('ADMIN', 'OPERATOR', 'VIEWER');

-- 5) 校验:必须至少有一个有效管理员,否则用户管理将无人可用
SELECT
  (SELECT COUNT(*) FROM si_user WHERE role = 'ADMIN' AND (status IS NULL OR status <> 'DISABLED')) AS active_admins,
  (SELECT COUNT(*) FROM si_user WHERE role = 'OPERATOR') AS operators,
  (SELECT COUNT(*) FROM si_user WHERE role = 'VIEWER')   AS viewers;
-- active_admins 必须 >= 1 才能进行下一步(切 ENFORCE)。
