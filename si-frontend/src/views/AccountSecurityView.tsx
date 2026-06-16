import { useState } from 'react'
import { changeOwnPassword, logoutAllDevices } from '../api'
import { clearAccessToken } from '../api/authToken'
import { ROUTES, STORAGE_KEYS } from '../constants'
import './AccountSecurityView.css'

const PASSWORD_MIN_LENGTH = 6

export default function AccountSecurityView() {
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordBusy, setPasswordBusy] = useState(false)
  const [logoutBusy, setLogoutBusy] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')

  const flashSuccess = (message: string) => {
    setSuccess(message)
    window.setTimeout(() => setSuccess(''), 2500)
  }

  const handlePasswordSubmit = async (event: React.FormEvent) => {
    event.preventDefault()
    setError('')
    if (!currentPassword || newPassword.length < PASSWORD_MIN_LENGTH) {
      setError('请输入当前密码,新密码至少 6 位')
      return
    }
    if (newPassword !== confirmPassword) {
      setError('两次输入的新密码不一致')
      return
    }
    setPasswordBusy(true)
    try {
      const result = await changeOwnPassword(currentPassword, newPassword)
      if (result.code === 200) {
        setCurrentPassword('')
        setNewPassword('')
        setConfirmPassword('')
        flashSuccess('密码已更新,旧令牌已失效')
      } else {
        setError(result.message || '修改密码失败')
      }
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '修改密码失败')
    } finally {
      setPasswordBusy(false)
    }
  }

  const handleLogoutAll = async () => {
    if (!window.confirm('退出所有设备后需要重新登录,确认继续？')) return
    setError('')
    setLogoutBusy(true)
    try {
      const result = await logoutAllDevices()
      if (result.code !== 200) {
        setError(result.message || '退出所有设备失败')
        return
      }
      clearAccessToken()
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
      localStorage.removeItem(STORAGE_KEYS.USER_ID)
      localStorage.removeItem(STORAGE_KEYS.ROLE)
      window.location.hash = ROUTES.LOGIN
    } catch (err) {
      setError((err as { response?: { data?: { message?: string } } })?.response?.data?.message || '退出所有设备失败')
    } finally {
      setLogoutBusy(false)
    }
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">账号安全</h1>
          <span className="si-brand-sub">密码与登录设备</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>返回同传</button>
        </div>
      </header>

      <main className="account-security-main">
        {error && <div className="account-security-banner account-security-banner--error">{error}</div>}
        {success && <div className="account-security-banner account-security-banner--success">{success}</div>}

        <section className="account-security-panel">
          <h2>修改密码</h2>
          <form className="account-security-form" onSubmit={handlePasswordSubmit}>
            <label>
              当前密码
              <input
                type="password"
                value={currentPassword}
                onChange={event => setCurrentPassword(event.target.value)}
                autoComplete="current-password"
              />
            </label>
            <label>
              新密码
              <input
                type="password"
                value={newPassword}
                onChange={event => setNewPassword(event.target.value)}
                autoComplete="new-password"
              />
            </label>
            <label>
              确认新密码
              <input
                type="password"
                value={confirmPassword}
                onChange={event => setConfirmPassword(event.target.value)}
                autoComplete="new-password"
              />
            </label>
            <button type="submit" disabled={passwordBusy}>
              {passwordBusy ? '保存中...' : '保存新密码'}
            </button>
          </form>
        </section>

        <section className="account-security-panel account-security-panel--danger">
          <h2>退出所有设备</h2>
          <p>这会让当前账号已签发的访问令牌立即失效,包括当前浏览器。</p>
          <button type="button" disabled={logoutBusy} onClick={handleLogoutAll}>
            {logoutBusy ? '处理中...' : '退出所有设备'}
          </button>
        </section>
      </main>
    </div>
  )
}
