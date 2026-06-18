/**
 * 登录页面
 */
import { useState } from 'react'
import { issueLoginCaptcha, login } from '../api'
import { setAccessToken } from '../api/authToken'
import { STORAGE_KEYS, ROUTES, HTTP_STATUS } from '../constants'
import ErrorBanner from '../components/ErrorBanner'
import './LoginView.css'

export default function LoginView() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [captchaId, setCaptchaId] = useState('')
  const [captchaQuestion, setCaptchaQuestion] = useState('')
  const [captchaAnswer, setCaptchaAnswer] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const resetCaptcha = () => {
    setCaptchaId('')
    setCaptchaQuestion('')
    setCaptchaAnswer('')
  }

  const loadCaptcha = async () => {
    const res = await issueLoginCaptcha(username)
    if (res.code === HTTP_STATUS.OK && res.data) {
      setCaptchaId(res.data.captchaId)
      setCaptchaQuestion(res.data.question)
      setCaptchaAnswer('')
      return
    }
    setError(res.message || '验证码获取失败')
  }

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const res = await login(username, password, captchaId || undefined, captchaAnswer || undefined)
      if (res.code === HTTP_STATUS.OK && res.data) {
        setAccessToken(res.data.token)
        localStorage.setItem(STORAGE_KEYS.USER_ID, String(res.data.userId))
        localStorage.setItem(STORAGE_KEYS.ROLE, res.data.role || '')
        localStorage.removeItem(STORAGE_KEYS.TOKEN)
        window.location.hash = res.data.role?.toUpperCase() === 'ADMIN' ? ROUTES.ADMIN_CONSOLE : ROUTES.HOME
      } else if (res.code === HTTP_STATUS.CAPTCHA_REQUIRED) {
        await loadCaptcha()
        setError(res.message || '请输入验证码后重试')
      } else {
        setError(res.message || '登录失败')
      }
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : '网络错误')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-container">
      <form className="login-form" onSubmit={handleLogin}>
        <h1 className="login-title">聚龙同传系统</h1>
        <p className="login-subtitle">Simultaneous Interpretation System</p>

        <div className="form-group">
          <label htmlFor="username">用户名</label>
          <input
            id="username"
            type="text"
            value={username}
            onChange={e => {
              setUsername(e.target.value)
              resetCaptcha()
            }}
            placeholder="请输入用户名"
            required
            autoFocus
          />
        </div>

        <div className="form-group">
          <label htmlFor="password">密码</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder="请输入密码"
            required
          />
        </div>

        {captchaId && (
          <div className="form-group captcha-group">
            <label htmlFor="captchaAnswer">验证码</label>
            <div className="captcha-row">
              <div className="captcha-question" aria-live="polite">{captchaQuestion}</div>
              <input
                id="captchaAnswer"
                type="text"
                inputMode="numeric"
                value={captchaAnswer}
                onChange={e => setCaptchaAnswer(e.target.value)}
                placeholder="答案"
                required
              />
              <button type="button" className="captcha-refresh" onClick={loadCaptcha} disabled={loading}>
                刷新
              </button>
            </div>
          </div>
        )}

        <ErrorBanner message={error} onDismiss={() => setError('')} />

        <button type="submit" className="login-button" disabled={loading}>
          {loading ? '登录中...' : '登录'}
        </button>
      </form>
    </div>
  )
}
