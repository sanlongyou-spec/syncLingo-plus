import { useEffect, useMemo, useState } from 'react'
import {
  getSpeakerIdentities,
  deleteSpeakerIdentity,
  getSessionSpeakerIdentities,
  mapSessionSpeakerIdentity,
} from '../api'
import type { SessionSpeakerIdentity, SpeakerIdentity } from '../types'
import { STORAGE_KEYS, ROUTES } from '../constants'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './InterpretationView.css'
import './VoiceCloneView.css'

export default function VoiceCloneView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const currentSessionId = localStorage.getItem(STORAGE_KEYS.CURRENT_SESSION_ID) || ''
  const [identities, setIdentities] = useState<SpeakerIdentity[]>([])
  const [sessionSpeakers, setSessionSpeakers] = useState<SessionSpeakerIdentity[]>([])
  const [mappingDraft, setMappingDraft] = useState<Record<string, string>>({})
  const [remappingIds, setRemappingIds] = useState<Set<string>>(new Set())
  const [error, setError] = useState('')
  const [successMsg, setSuccessMsg] = useState('')

  // suppress unused warning – userId is kept for future per-user filtering
  void userId

  const knownNames = useMemo(
    () => identities.map(item => item.personName).filter(Boolean),
    [identities],
  )

  const loadIdentities = async () => {
    const res = await getSpeakerIdentities()
    setIdentities(res.data || [])
  }

  const loadSessionSpeakers = async () => {
    if (!currentSessionId) return
    const res = await getSessionSpeakerIdentities(currentSessionId)
    const rows = res.data || []
    setSessionSpeakers(rows)
    setMappingDraft(prev => {
      const next = { ...prev }
      rows.forEach(row => {
        if (!next[row.speakerId]) {
          next[row.speakerId] = row.personName || ''
        }
      })
      return next
    })
  }

  useEffect(() => {
    void loadIdentities().catch((err: unknown) => {
      setError(err instanceof Error ? err.message : '加载声纹表失败')
    })
  }, [])

  useEffect(() => {
    void loadSessionSpeakers().catch((err: unknown) => {
      console.warn('[VoiceCloneView] loadSessionSpeakers failed:', err)
    })
    if (!currentSessionId) return
    const timer = window.setInterval(() => {
      void loadSessionSpeakers().catch((err: unknown) => {
        console.warn('[VoiceCloneView] poll session speakers failed:', err)
      })
    }, 3000)
    return () => window.clearInterval(timer)
  }, [currentSessionId])

  const removeIdentity = async (id?: number) => {
    if (!id || !window.confirm('确定删除这条声纹记录？')) return
    await deleteSpeakerIdentity(id)
    await loadIdentities()
  }

  const applySessionMapping = async (speakerId: string) => {
    if (!currentSessionId) return
    const personName = mappingDraft[speakerId]?.trim()
    if (!personName) {
      setError('请填写真实人名')
      return
    }
    await mapSessionSpeakerIdentity(currentSessionId, speakerId, personName)
    setRemappingIds(prev => { const s = new Set(prev); s.delete(speakerId); return s })
    await loadSessionSpeakers()
    await loadIdentities()
    setSuccessMsg(`已关联：${personName}（下次会议自动识别）`)
  }

  const startRemap = (speakerId: string, currentName: string) => {
    setMappingDraft(prev => ({ ...prev, [speakerId]: currentName }))
    setRemappingIds(prev => new Set(prev).add(speakerId))
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">音色克隆</h1>
          <span className="si-brand-sub">维护真实人名、声纹编码和 Cartesia 音色</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="vc-main">
        <section className="vc-panel vc-panel--identity">
          <div className="vc-section-head">
            <h2 className="vc-panel-title">声纹对照表</h2>
          </div>

          <ErrorBanner message={error} onDismiss={() => setError('')} />
          {successMsg && <SuccessBanner message={successMsg} />}

          <div className="vc-table">
            <div className="vc-table-row vc-table-row--head">
              <span>真实人名</span>
              <span>声纹编码</span>
              <span>voiceId</span>
              <span>操作</span>
            </div>
            {identities.map(identity => (
              <div className="vc-table-row" key={identity.id || identity.personName}>
                <strong>{identity.personName}</strong>
                <code className={identity.speakerProfileId ? '' : 'vc-code--empty'}>
                  {identity.speakerProfileId
                    ? (identity.speakerProfileId === 'enrolled' ? '已注册' : identity.speakerProfileId.slice(0, 8) + '…')
                    : '未注册'}
                </code>
                <code className={identity.cartesiaVoiceId ? '' : 'vc-code--empty'}>
                  {identity.cartesiaVoiceId ? identity.cartesiaVoiceId.slice(0, 8) + '…' : '待自动生成'}
                </code>
                <span className="vc-row-actions">
                  <button onClick={() => removeIdentity(identity.id)}>删除</button>
                </span>
              </div>
            ))}
            {identities.length === 0 && <div className="vc-empty">暂无声纹记录</div>}
          </div>

          <div className="vc-session-map">
            <div className="vc-section-head">
              <div>
                <h2 className="vc-panel-title">本次会议说话人</h2>
                <p className="vc-panel-desc">
                  关联真实人名后自动注册声纹、克隆音色，下次会议无需重复填写。
                </p>
              </div>
              {currentSessionId && <code className="vc-session-id">{currentSessionId.slice(0, 8)}</code>}
            </div>
            {!currentSessionId && <div className="vc-empty">同传开始后会显示本次会议说话人</div>}
            {currentSessionId && sessionSpeakers.length === 0 && (
              <div className="vc-empty">等待 ASR 识别到说话人...</div>
            )}
            {sessionSpeakers.map(row => {
              const isMapped = !!row.personName && !remappingIds.has(row.speakerId)
              return (
                <div className="vc-speaker-map-row" key={row.speakerId}>
                  {isMapped ? (
                    <>
                      <div>
                        <span className="vc-speaker-pill vc-speaker-pill--named">{row.personName}</span>
                        <span className={`vc-status vc-status--${(row.status || 'unknown').toLowerCase()}`}>
                          {row.status === 'IDENTIFIED' ? '声纹识别' : row.status === 'MANUAL' ? '手动关联' : row.status || 'UNKNOWN'}
                        </span>
                        <span className="vc-speaker-id-sub">{row.speakerId}</span>
                      </div>
                      <div className="vc-speaker-voice-info">
                        {row.cartesiaVoiceId
                          ? <code className="vc-voice-bound">音色已绑定 {row.cartesiaVoiceId.slice(0, 8)}…</code>
                          : <span className="vc-code--empty">克隆完成后自动绑定</span>}
                      </div>
                      <button onClick={() => startRemap(row.speakerId, row.personName || '')}>修改</button>
                    </>
                  ) : (
                    <>
                      <div>
                        <span className="vc-speaker-pill">{row.speakerId}</span>
                        {row.personName && (
                          <span className="vc-status">原: {row.personName}</span>
                        )}
                      </div>
                      <input
                        list="known-speaker-names"
                        value={mappingDraft[row.speakerId] || ''}
                        onChange={e => setMappingDraft(prev => ({ ...prev, [row.speakerId]: e.target.value }))}
                        placeholder="选择或输入真实人名"
                        autoFocus={remappingIds.has(row.speakerId)}
                      />
                      <button onClick={() => applySessionMapping(row.speakerId)}>关联</button>
                    </>
                  )}
                </div>
              )
            })}
            <datalist id="known-speaker-names">
              {knownNames.map(name => <option key={name} value={name} />)}
            </datalist>
          </div>
        </section>
      </main>
    </div>
  )
}
