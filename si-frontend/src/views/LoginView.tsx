/**
 * 登录页面
 */
import { useState } from 'react'
import { login } from '../api'
import { STORAGE_KEYS, ROUTES, HTTP_STATUS } from '../constants'
import ErrorBanner from '../components/ErrorBanner'
import './LoginView.css'

export default function LoginView() {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault()
    setError('')
    setLoading(true)

    try {
      const res = await login(username, password)
      if (res.code === HTTP_STATUS.OK && res.data) {
        localStorage.setItem(STORAGE_KEYS.USER_ID, String(res.data.userId))
        localStorage.setItem(STORAGE_KEYS.TOKEN, res.data.token)
        window.location.hash = ROUTES.HOME
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
        <h1 className="login-title">同声传译系统</h1>
        <p className="login-subtitle">Simultaneous Interpretation System</p>

        <div className="form-group">
          <label htmlFor="username">用户名</label>
          <input
            id="username"
            type="text"
            value={username}
            onChange={e => setUsername(e.target.value)}
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

        <ErrorBanner message={error} onDismiss={() => setError('')} />

        <button type="submit" className="login-button" disabled={loading}>
          {loading ? '登录中...' : '登录'}
        </button>
      </form>
    </div>
  )
}
