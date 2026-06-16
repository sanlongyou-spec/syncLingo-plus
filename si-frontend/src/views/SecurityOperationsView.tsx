import { FormEvent, useEffect, useState } from 'react'
import {
  approveSupportGrant,
  assignMeetingMember,
  listAuditLogs,
  listMeetingMembers,
  listSupportGrants,
  requestSupportGrant,
  revokeMeetingMember,
  revokeSupportGrant,
} from '../api'
import { ROUTES } from '../constants'
import type { AuditLog, MeetingMember, SupportAccessGrant } from '../types'
import './SecurityOperationsView.css'

type AccessLevel = 'VIEW' | 'OPERATE'

const parsePositiveInt = (value: string) => {
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : null
}

export default function SecurityOperationsView() {
  const [meetingId, setMeetingId] = useState('')
  const [targetUserId, setTargetUserId] = useState('')
  const [accessLevel, setAccessLevel] = useState<AccessLevel>('VIEW')
  const [members, setMembers] = useState<MeetingMember[]>([])

  const [grantMeetingId, setGrantMeetingId] = useState('')
  const [grantReason, setGrantReason] = useState('')
  const [grantTtl, setGrantTtl] = useState('30')
  const [grants, setGrants] = useState<SupportAccessGrant[]>([])

  const [auditLogs, setAuditLogs] = useState<AuditLog[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const flash = (message: string) => {
    setSuccess(message)
    window.setTimeout(() => setSuccess(''), 2500)
  }

  const loadGrantsAndAudit = async () => {
    const [grantRes, auditRes] = await Promise.all([
      listSupportGrants().catch(() => null),
      listAuditLogs().catch(() => null),
    ])
    if (grantRes?.code === 200) setGrants(grantRes.data || [])
    if (auditRes?.code === 200) setAuditLogs(auditRes.data || [])
  }

  useEffect(() => {
    void loadGrantsAndAudit()
  }, [])

  const loadMembers = async () => {
    setError('')
    const id = parsePositiveInt(meetingId)
    if (!id) {
      setError('请输入有效的会议 ID')
      return
    }
    setLoading(true)
    try {
      const res = await listMeetingMembers(id)
      if (res.code === 200) setMembers(res.data || [])
      else setError(res.message || '读取会议成员失败')
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '读取会议成员失败')
    } finally {
      setLoading(false)
    }
  }

  const submitMember = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    const id = parsePositiveInt(meetingId)
    const userId = parsePositiveInt(targetUserId)
    if (!id || !userId) {
      setError('会议 ID 和用户 ID 必须为正整数')
      return
    }
    setLoading(true)
    try {
      const res = await assignMeetingMember(id, userId, accessLevel)
      if (res.code === 200) {
        flash('会议成员授权已保存')
        await loadMembers()
      } else {
        setError(res.message || '保存授权失败')
      }
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '保存授权失败')
    } finally {
      setLoading(false)
    }
  }

  const revokeMember = async (userId: number) => {
    const id = parsePositiveInt(meetingId)
    if (!id) return
    setLoading(true)
    try {
      const res = await revokeMeetingMember(id, userId)
      if (res.code === 200) {
        flash('会议成员授权已撤销')
        await loadMembers()
      } else {
        setError(res.message || '撤销授权失败')
      }
    } finally {
      setLoading(false)
    }
  }

  const submitGrant = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    const id = parsePositiveInt(grantMeetingId)
    const ttl = parsePositiveInt(grantTtl)
    if (!id || !ttl || !grantReason.trim()) {
      setError('会议 ID、原因和有效期必填')
      return
    }
    setLoading(true)
    try {
      const res = await requestSupportGrant(id, grantReason.trim(), ttl)
      if (res.code === 200) {
        flash('支持授权申请已提交')
        setGrantReason('')
        await loadGrantsAndAudit()
      } else {
        setError(res.message || '提交支持授权失败')
      }
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '提交支持授权失败')
    } finally {
      setLoading(false)
    }
  }

  const runGrantAction = async (id: number, action: 'approve' | 'revoke') => {
    setLoading(true)
    try {
      const res = action === 'approve' ? await approveSupportGrant(id) : await revokeSupportGrant(id)
      if (res.code === 200) {
        flash(action === 'approve' ? '支持授权已批准' : '支持授权已撤销')
        await loadGrantsAndAudit()
      } else {
        setError(res.message || '支持授权操作失败')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">安全运维</h1>
          <span className="si-brand-sub">成员授权、支持访问与审计</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>返回同传</button>
        </div>
      </header>

      <main className="secops-main">
        {error && <div className="secops-banner secops-banner--error">{error}</div>}
        {success && <div className="secops-banner secops-banner--success">{success}</div>}

        <section className="secops-panel">
          <h2>会议成员授权</h2>
          <form className="secops-form secops-form--inline" onSubmit={submitMember}>
            <label>会议 ID<input value={meetingId} onChange={e => setMeetingId(e.target.value)} /></label>
            <label>用户 ID<input value={targetUserId} onChange={e => setTargetUserId(e.target.value)} /></label>
            <label>级别
              <select value={accessLevel} onChange={e => setAccessLevel(e.target.value as AccessLevel)}>
                <option value="VIEW">VIEW</option>
                <option value="OPERATE">OPERATE</option>
              </select>
            </label>
            <button type="button" onClick={() => void loadMembers()} disabled={loading}>读取</button>
            <button type="submit" disabled={loading}>保存授权</button>
          </form>
          <div className="secops-table-wrap">
            <table className="secops-table">
              <thead><tr><th>用户 ID</th><th>用户名</th><th>级别</th><th>操作</th></tr></thead>
              <tbody>
                {members.length === 0 ? (
                  <tr><td colSpan={4}>暂无成员</td></tr>
                ) : members.map(member => (
                  <tr key={member.userId}>
                    <td>{member.userId}</td>
                    <td>{member.username || '-'}</td>
                    <td>{member.accessLevel}</td>
                    <td><button className="secops-mini" onClick={() => void revokeMember(member.userId)} disabled={loading}>撤销</button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="secops-panel">
          <h2>支持访问授权</h2>
          <form className="secops-form secops-form--grant" onSubmit={submitGrant}>
            <label>会议 ID<input value={grantMeetingId} onChange={e => setGrantMeetingId(e.target.value)} /></label>
            <label>有效期(分钟)<input value={grantTtl} onChange={e => setGrantTtl(e.target.value)} /></label>
            <label>原因<input value={grantReason} onChange={e => setGrantReason(e.target.value)} /></label>
            <button type="submit" disabled={loading}>提交申请</button>
          </form>
          <div className="secops-table-wrap">
            <table className="secops-table">
              <thead><tr><th>ID</th><th>会议</th><th>申请人</th><th>批准人</th><th>到期</th><th>操作</th></tr></thead>
              <tbody>
                {grants.length === 0 ? (
                  <tr><td colSpan={6}>暂无支持授权</td></tr>
                ) : grants.map(grant => (
                  <tr key={grant.id}>
                    <td>{grant.id}</td>
                    <td>{grant.resourceId}</td>
                    <td>{grant.requestedBy}</td>
                    <td>{grant.approvedBy ?? '-'}</td>
                    <td>{grant.expiresAt || '-'}</td>
                    <td className="secops-actions">
                      <button className="secops-mini" onClick={() => void runGrantAction(grant.id, 'approve')} disabled={loading || !!grant.approvedBy || !!grant.revokedAt}>批准</button>
                      <button className="secops-mini secops-mini--ghost" onClick={() => void runGrantAction(grant.id, 'revoke')} disabled={loading || !!grant.revokedAt}>撤销</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>

        <section className="secops-panel">
          <h2>审计日志</h2>
          <div className="secops-table-wrap">
            <table className="secops-table">
              <thead><tr><th>时间</th><th>操作者</th><th>动作</th><th>资源</th><th>结果</th><th>详情</th></tr></thead>
              <tbody>
                {auditLogs.length === 0 ? (
                  <tr><td colSpan={6}>暂无审计记录</td></tr>
                ) : auditLogs.map(log => (
                  <tr key={log.id}>
                    <td>{log.createTime || '-'}</td>
                    <td>{log.actorType}:{log.actorId || '-'}</td>
                    <td>{log.action}</td>
                    <td>{log.resourceType || '-'}:{log.resourceId || '-'}</td>
                    <td>{log.result}</td>
                    <td>{log.detail || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      </main>
    </div>
  )
}
