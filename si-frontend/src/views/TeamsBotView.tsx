import { useCallback, useEffect, useState } from 'react'
import { joinMeeting, getMeetingParticipants, sendTeamsSummaryToUsers, sendSummaryToMeetingChat } from '../api'
import { ROUTES } from '../constants'
import type { MeetingParticipant } from '../types'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import './TeamsBotView.css'

export default function TeamsBotView() {
  const [meetingUrl, setMeetingUrl] = useState('')
  const [joinStatus, setJoinStatus] = useState<'idle' | 'joining' | 'joined' | 'error'>('idle')
  const [activeCallId, setActiveCallId] = useState<string | null>(null)

  const [participants, setParticipants] = useState<MeetingParticipant[]>([])
  const [participantsLoading, setParticipantsLoading] = useState(false)

  const [sendStatus, setSendStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')
  const [chatSendStatus, setChatSendStatus] = useState<'idle' | 'sending' | 'sent' | 'error'>('idle')
  const [summaryContent, setSummaryContent] = useState('')

  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const fetchParticipants = useCallback(async () => {
    setParticipantsLoading(true)
    try {
      const data = await getMeetingParticipants()
      setParticipants(data.participants)
      if (data.callId) setActiveCallId(data.callId)
    } catch (e) {
      setError(e instanceof Error ? e.message : '获取参会人员失败')
    } finally {
      setParticipantsLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchParticipants()
  }, [fetchParticipants])

  const handleJoinMeeting = async () => {
    const url = meetingUrl.trim()
    if (!url) { setError('请粘贴 Teams 会议链接'); return }
    setJoinStatus('joining')
    setError('')
    try {
      const data = await joinMeeting(url)
      setActiveCallId(data.callId)
      setJoinStatus('joined')
      setSuccess('机器人已加入会议，正在获取参会人员…')
      setMeetingUrl('')
      setTimeout(fetchParticipants, 3000)
    } catch (e) {
      setJoinStatus('error')
      setError(e instanceof Error ? e.message : '加入会议失败')
    }
  }

  const handleSendToParticipants = async () => {
    const content = summaryContent.trim()
    if (!content) { setError('请填写摘要内容'); return }
    if (participants.length === 0) { setError('参会人员列表为空，请先让机器人加入会议'); return }

    const recipients = participants.map(p => p.aadId).filter(Boolean)
    if (recipients.length === 0) { setError('参会人员信息不完整，请刷新后重试'); return }

    setSendStatus('sending')
    setError('')
    setSuccess('')
    try {
      const res = await sendTeamsSummaryToUsers(content, recipients)
      if (res.sentCount === 0) throw new Error(res.failures[0]?.error || '没有 Teams 用户收到摘要')
      setSendStatus('sent')
      setSuccess(res.failedCount > 0
        ? `已发送给 ${res.sentCount} 人，${res.failedCount} 人失败`
        : `已成功发送给全部 ${res.sentCount} 位参会人员`)
      setSummaryContent('')
    } catch (e) {
      setSendStatus('error')
      setError(`发送失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const handleSendToMeetingChat = async () => {
    const content = summaryContent.trim()
    if (!content) { setError('请填写摘要内容'); return }
    if (!activeCallId) { setError('机器人尚未加入会议，请先加入会议'); return }

    setChatSendStatus('sending')
    setError('')
    setSuccess('')
    try {
      await sendSummaryToMeetingChat(content)
      setChatSendStatus('sent')
      setSuccess('摘要已发送到会议聊天，所有参会人员可见')
      setSummaryContent('')
    } catch (e) {
      setChatSendStatus('error')
      setError(`发送失败：${e instanceof Error ? e.message : String(e)}`)
    }
  }

  const botStatus = activeCallId
    ? '机器人已在会议中'
    : joinStatus === 'joined' ? '机器人已加入' : '机器人未在会议中'

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">Teams Bot</h1>
          <span className="si-brand-sub">会议集成 · 摘要推送</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="tb-main">
        <ErrorBanner message={error} onDismiss={() => setError('')} />
        {success && <SuccessBanner message={success} />}

        {/* Section 1: Join Meeting */}
        <section className="tb-card">
          <h2 className="tb-card-title">加入会议</h2>
          <p className="tb-card-desc">
            粘贴 Teams 会议链接，机器人将加入会议并自动识别参会人员。
          </p>
          <div className="tb-add-row">
            <input
              className="tb-input"
              placeholder="https://teams.microsoft.com/l/meetup-join/..."
              value={meetingUrl}
              onChange={e => setMeetingUrl(e.target.value)}
              onKeyDown={e => e.key === 'Enter' && handleJoinMeeting()}
              disabled={!!activeCallId}
            />
            <button
              className={`tb-btn tb-btn--primary ${joinStatus === 'joining' ? 'tb-btn--loading' : ''}`}
              onClick={handleJoinMeeting}
              disabled={joinStatus === 'joining' || !!activeCallId}
            >
              {joinStatus === 'joining' ? '加入中…' : '加入会议'}
            </button>
          </div>
          <p className={`tb-status ${activeCallId ? 'tb-status--ok' : 'tb-status--idle'}`}>
            {botStatus}
          </p>
        </section>

        {/* Section 2: Participants */}
        <section className="tb-card">
          <div className="tb-card-header">
            <h2 className="tb-card-title">参会人员</h2>
            <button
              className="tb-btn tb-btn--secondary tb-btn--sm"
              onClick={fetchParticipants}
              disabled={participantsLoading}
            >
              {participantsLoading ? '刷新中…' : '刷新'}
            </button>
          </div>
          {participants.length === 0 ? (
            <p className="tb-empty">
              {participantsLoading ? '正在获取参会人员…' : '暂无参会人员（机器人尚未加入会议，或会议中还没有其他人）'}
            </p>
          ) : (
            <div className="tb-recipient-list">
              {participants.map(p => (
                <div key={p.aadId} className="tb-recipient-row">
                  <span className="tb-type-pill tb-type-pill--person">参会</span>
                  <span className="tb-recipient-value">
                    {p.displayName || p.aadId}
                    {p.email && <span className="tb-recipient-sub"> · {p.email}</span>}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Section 3: Send Summary */}
        <section className="tb-card">
          <h2 className="tb-card-title">发送会议摘要</h2>
          <p className="tb-card-desc">
            粘贴摘要内容后，可发送到会议聊天（所有参会人员可见），或以私聊形式分别发送给每位参会人员。
          </p>
          <textarea
            className="tb-textarea"
            rows={5}
            placeholder="在此粘贴摘要内容…"
            value={summaryContent}
            onChange={e => setSummaryContent(e.target.value)}
          />
          <div className="tb-send-row">
            <span className="tb-send-hint">
              将发送给 {participants.length} 位参会人员
            </span>
            <button
              className={`tb-btn tb-btn--secondary ${chatSendStatus === 'sending' ? 'tb-btn--loading' : ''}`}
              onClick={handleSendToMeetingChat}
              disabled={chatSendStatus === 'sending' || !activeCallId}
            >
              {chatSendStatus === 'sending' ? '发送中…' : '发送到会议聊天'}
            </button>
            <button
              className={`tb-btn tb-btn--primary ${sendStatus === 'sending' ? 'tb-btn--loading' : ''}`}
              onClick={handleSendToParticipants}
              disabled={sendStatus === 'sending' || participants.length === 0}
            >
              {sendStatus === 'sending' ? '发送中…' : '发送给参会人员'}
            </button>
          </div>
        </section>
      </main>
    </div>
  )
}
