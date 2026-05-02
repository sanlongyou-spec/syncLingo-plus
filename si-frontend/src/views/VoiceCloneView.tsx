/**
 * 音色克隆页面
 * 允许用户上传音频样本克隆音色
 */
import { useState, useEffect } from 'react'
import { cloneVoice, getUserVoice, deleteUserVoice } from '../api'
import type { CloneVoiceResponse } from '../types'
import { VOICE_CLONE_LANGUAGE_OPTIONS } from '../types'
import { STORAGE_KEYS, ROUTES, VOICE_CLONE, LANGUAGE, HTTP_STATUS } from '../constants'
import Header from '../components/Header'
import ErrorBanner from '../components/ErrorBanner'
import SuccessBanner from '../components/SuccessBanner'
import AudioPreview from '../components/AudioPreview'
import AudioUploader from '../components/AudioUploader'
import './VoiceCloneView.css'

export default function VoiceCloneView() {
  // 默认用户 ID（移除登录后使用固定用户）
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const [voiceName, setVoiceName] = useState('')
  const [language, setLanguage] = useState<string>(LANGUAGE.ZH)
  const [audioFile, setAudioFile] = useState<File | null>(null)
  const [audioUrl, setAudioUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<CloneVoiceResponse | null>(null)
  const [error, setError] = useState('')
  const [successMsg, setSuccessMsg] = useState('')

  useEffect(() => {
    getUserVoice(userId)
      .then(res => {
        if (res.data?.voiceId) {
          setResult({
            voiceId: res.data.voiceId,
            voiceName: res.data.voiceName || '',
            durationSeconds: res.data.durationSeconds || 0,
            createTime: res.data.createTime || '',
          })
        }
      })
      .catch((err: unknown) => {
        // 音色不存在不影响页面加载，静默忽略
        console.warn('[VoiceCloneView] getUserVoice failed:', err)
      })
  }, [userId])

  const handleFileChange = (file: File) => {
    setAudioFile(file)
    setAudioUrl(URL.createObjectURL(file))
    setError('')
    setResult(null)
  }

  const handleClone = async () => {
    if (!audioFile || !voiceName.trim()) {
      setError('请填写音色名称并上传音频样本')
      return
    }
    setError('')
    setSuccessMsg('')
    setLoading(true)

    try {
      const buffer = await audioFile.arrayBuffer()
      const base64 = btoa(
        new Uint8Array(buffer).reduce((data, byte) => data + String.fromCharCode(byte), '')
      )

      const res = await cloneVoice({
        userId,
        voiceName: voiceName.trim(),
        audioSample: base64,
        language,
      })

      if (res.code === HTTP_STATUS.OK && res.data) {
        setResult(res.data)
        setSuccessMsg('音色克隆成功！')
      } else {
        setError(res.message || '克隆失败')
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '克隆失败，请检查音频格式')
    } finally {
      setLoading(false)
    }
  }

  const handleDelete = async () => {
    try {
      await deleteUserVoice(userId)
      setResult(null)
      setSuccessMsg('')
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '删除失败')
    }
  }

  return (
    <div className="voice-clone-view">
      <Header
        title="音色克隆"
        actions={
          <button
            className="btn-link"
            onClick={() => { window.location.hash = ROUTES.HOME }}
          >
            返回同传
          </button>
        }
      />

      <main className="main-content">
        <div className="clone-card">
          <h2 className="card-title">克隆新音色</h2>
          <p className="card-desc">
            上传一段 {VOICE_CLONE.RECOMMENDED_AUDIO_SECONDS} 秒的语音样本，
            系统将克隆你的音色用于 TTS 输出（建议录音与目标语言一致）
          </p>

          <div className="form-group">
            <label>音色名称</label>
            <input
              type="text"
              value={voiceName}
              onChange={e => setVoiceName(e.target.value)}
              placeholder="例如：我的声音"
              maxLength={VOICE_CLONE.MAX_NAME_LENGTH}
            />
          </div>

          <div className="form-group">
            <label>音色语言</label>
            <div className="lang-options">
              {VOICE_CLONE_LANGUAGE_OPTIONS.map(opt => (
                <button
                  key={opt.value}
                  className={`lang-option ${language === opt.value ? 'lang-option--active' : ''}`}
                  onClick={() => setLanguage(opt.value)}
                  type="button"
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <div className="form-group">
            <label>
              音频样本（建议 {VOICE_CLONE.RECOMMENDED_AUDIO_SECONDS} 秒，WAV / MP3，16kHz 单声道效果最佳）
            </label>
            <AudioUploader onChange={handleFileChange} />
            {audioFile && <span className="file-name">{audioFile.name}</span>}
          </div>

          {audioUrl && <AudioPreview src={audioUrl} />}

          <ErrorBanner message={error} onDismiss={() => setError('')} />

          <button
            className="btn-clone"
            onClick={handleClone}
            disabled={loading || !audioFile || !voiceName.trim()}
          >
            {loading ? '克隆中...' : '开始克隆'}
          </button>

          {result && (
            <SuccessBanner message={successMsg}>
              <code className="voice-id-display">{result.voiceId}</code>
            </SuccessBanner>
          )}
        </div>

        <div className="voice-list-card">
          <h2 className="card-title">我的音色</h2>
          {result ? (
            <div className="voice-item">
              <div className="voice-info">
                <div className="voice-name">{result.voiceName}</div>
                <div className="voice-id">{result.voiceId}</div>
              </div>
              <button className="btn-delete" onClick={handleDelete}>删除</button>
            </div>
          ) : (
            <div className="empty-state">暂无克隆音色</div>
          )}
        </div>
      </main>
    </div>
  )
}
