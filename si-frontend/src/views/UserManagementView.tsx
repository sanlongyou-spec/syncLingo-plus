import { useEffect, useState } from 'react'
import { createUser, listUsers, resetUserPassword, updateUserRole, updateUserStatus } from '../api'
import { ROUTES } from '../constants'
import type { UserSummary } from '../types'
import './UserManagementView.css'

const ROLES = ['ADMIN', 'OPERATOR', 'VIEWER'] as const
const ROLE_LABEL: Record<string, string> = { ADMIN: '管理员', OPERATOR: '操作员', VIEWER: '查看者' }
const STATUS_LABEL: Record<string, string> = { ACTIVE: '启用', PENDING: '待启用', DISABLED: '已停用' }

const EMPTY_FORM = { username: '', password: '', role: 'OPERATOR', nickname: '', email: '' }

export default function UserManagementView() {
  const [users, setUsers] = useState<UserSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [forbidden, setForbidden] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [busyId, setBusyId] = useState<number | null>(null)

  const [form, setForm] = useState(EMPTY_FORM)
  const [creating, setCreating] = useState(false)

  const [resettingId, setResettingId] = useState<number | null>(null)
  const [newPwd, setNewPwd] = useState('')

  const flash = (msg: string) => { setSuccess(msg); window.setTimeout(() => setSuccess(''), 2500) }

  const load = async () => {
    setLoading(true); setError('')
    try {
      const res = await listUsers()
      if (res.code === 200) {
        setUsers(res.data ?? [])
        setForbidden(false)
      } else if (res.code === 403) {
        setForbidden(true)
      } else {
        setError(res.message || '加载失败')
      }
    } catch (e) {
      const code = (e as { response?: { status?: number } })?.response?.status
      if (code === 403) setForbidden(true)
      else setError('加载失败,请稍后重试')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    if (!form.username.trim() || form.password.length < 6) {
      setError('用户名必填,密码至少 6 位')
      return
    }
    setCreating(true)
    try {
      const res = await createUser({
        username: form.username.trim(),
        password: form.password,
        role: form.role,
        nickname: form.nickname.trim() || undefined,
        email: form.email.trim() || undefined,
      })
      if (res.code === 200) {
        flash('已创建用户「' + res.data.username + '」')
        setForm(EMPTY_FORM)
        load()
      } else {
        setError(res.message || '创建失败')
      }
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '创建失败')
    } finally {
      setCreating(false)
    }
  }

  const runRowAction = async (id: number, fn: () => Promise<{ code: number; message: string }>, ok: string) => {
    setBusyId(id); setError('')
    try {
      const res = await fn()
      if (res.code === 200) { flash(ok); load() }
      else setError(res.message || '操作失败')
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '操作失败')
    } finally {
      setBusyId(null)
    }
  }

  const changeRole = (u: UserSummary, role: string) =>
    runRowAction(u.id, () => updateUserRole(u.id, role), '已更新角色')

  const toggleStatus = (u: UserSummary) => {
    const next = (u.status || 'ACTIVE') === 'DISABLED' ? 'ACTIVE' : 'DISABLED'
    runRowAction(u.id, () => updateUserStatus(u.id, next), next === 'DISABLED' ? '已停用' : '已启用')
  }

  const submitReset = (u: UserSummary) => {
    if (newPwd.length < 6) { setError('新密码至少 6 位'); return }
    runRowAction(u.id, () => resetUserPassword(u.id, newPwd).then(r => ({ code: r.code, message: r.message })), '已重置密码')
      .then(() => { setResettingId(null); setNewPwd('') })
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">用户管理</h1>
          <span className="si-brand-sub">账号、角色与启停(仅管理员)</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>返回同传</button>
        </div>
      </header>

      <main className="usermgmt-main">
        {error && <div className="usermgmt-banner usermgmt-banner--error">{error}</div>}
        {success && <div className="usermgmt-banner usermgmt-banner--success">{success}</div>}

        {forbidden ? (
          <div className="usermgmt-empty">仅管理员可访问用户管理。</div>
        ) : (
          <div className="usermgmt-grid">
            <section className="usermgmt-card">
              <h2>新增用户</h2>
              <form className="usermgmt-form" onSubmit={handleCreate}>
                <label>用户名<input value={form.username} onChange={e => setForm(p => ({ ...p, username: e.target.value }))} maxLength={50} /></label>
                <label>初始密码<input type="password" value={form.password} onChange={e => setForm(p => ({ ...p, password: e.target.value }))} placeholder="至少 6 位" /></label>
                <label>角色
                  <select value={form.role} onChange={e => setForm(p => ({ ...p, role: e.target.value }))}>
                    {ROLES.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                  </select>
                </label>
                <label>姓名(可选)<input value={form.nickname} onChange={e => setForm(p => ({ ...p, nickname: e.target.value }))} /></label>
                <label>邮箱(可选)<input value={form.email} onChange={e => setForm(p => ({ ...p, email: e.target.value }))} /></label>
                <button type="submit" disabled={creating}>{creating ? '创建中...' : '创建用户'}</button>
              </form>
            </section>

            <section className="usermgmt-card usermgmt-table-card">
              <h2>用户列表{!loading && <span className="usermgmt-count">{users.length}</span>}</h2>
              {loading ? (
                <div className="usermgmt-empty">加载中...</div>
              ) : users.length === 0 ? (
                <div className="usermgmt-empty">暂无用户</div>
              ) : (
                <table className="usermgmt-table">
                  <thead>
                    <tr><th>用户名</th><th>姓名</th><th>角色</th><th>状态</th><th>操作</th></tr>
                  </thead>
                  <tbody>
                    {users.map(u => {
                      const status = (u.status || 'ACTIVE').toUpperCase()
                      const disabled = status === 'DISABLED'
                      return (
                        <tr key={u.id} className={disabled ? 'is-disabled' : ''}>
                          <td>{u.username}</td>
                          <td>{u.nickname || '—'}</td>
                          <td>
                            <select
                              className="usermgmt-role-select"
                              value={ROLES.includes((u.role || '').toUpperCase() as typeof ROLES[number]) ? (u.role || '').toUpperCase() : ''}
                              disabled={busyId === u.id}
                              onChange={e => changeRole(u, e.target.value)}
                            >
                              {!ROLES.includes((u.role || '').toUpperCase() as typeof ROLES[number]) && <option value="">{u.role || '未设置'}</option>}
                              {ROLES.map(r => <option key={r} value={r}>{ROLE_LABEL[r]}</option>)}
                            </select>
                          </td>
                          <td>
                            <span className={'usermgmt-badge usermgmt-badge--' + status.toLowerCase()}>{STATUS_LABEL[status] || status}</span>
                          </td>
                          <td className="usermgmt-actions">
                            {resettingId === u.id ? (
                              <span className="usermgmt-reset-inline">
                                <input type="password" value={newPwd} placeholder="新密码≥6位" onChange={e => setNewPwd(e.target.value)} />
                                <button className="usermgmt-mini" disabled={busyId === u.id} onClick={() => submitReset(u)}>确认</button>
                                <button className="usermgmt-mini usermgmt-mini--ghost" onClick={() => { setResettingId(null); setNewPwd('') }}>取消</button>
                              </span>
                            ) : (
                              <>
                                <button className="usermgmt-mini" disabled={busyId === u.id} onClick={() => toggleStatus(u)}>
                                  {disabled ? '启用' : '停用'}
                                </button>
                                <button className="usermgmt-mini usermgmt-mini--ghost" disabled={busyId === u.id} onClick={() => { setResettingId(u.id); setNewPwd('') }}>
                                  重置密码
                                </button>
                              </>
                            )}
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              )}
            </section>
          </div>
        )}
      </main>
    </div>
  )
}
