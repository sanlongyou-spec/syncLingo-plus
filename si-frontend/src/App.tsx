/**
 * 应用根组件，路由配置
 */
import { useEffect, useState } from 'react'
import { HashRouter, Navigate, Routes, Route, useLocation } from 'react-router-dom'
import InterpretationView from './views/InterpretationView'
import LoginView from './views/LoginView'
import UserShareView from './views/UserShareView'
import HistoryView from './views/HistoryView'
import TerminologyView from './views/TerminologyView'
import TeamsBotView from './views/TeamsBotView'
import CostAnalysisView from './views/CostAnalysisView'
import UserManagementView from './views/UserManagementView'
import AccountSecurityView from './views/AccountSecurityView'
import SecurityOperationsView from './views/SecurityOperationsView'
import { STORAGE_KEYS } from './constants'
import { refreshAuth } from './api'
import { clearAccessToken, getAccessToken } from './api/authToken'

function RequireAuth({ children }: { children: JSX.Element }) {
  const [status, setStatus] = useState<'checking' | 'authenticated' | 'anonymous'>(() => {
    const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID))
    if (getAccessToken()) return 'authenticated'
    return Number.isSafeInteger(userId) && userId > 0 ? 'checking' : 'anonymous'
  })

  useEffect(() => {
    if (status !== 'checking') return
    let cancelled = false

    refreshAuth()
      .then(res => {
        if (cancelled) return
        if (res.code === 200 && res.data?.token) {
          localStorage.setItem(STORAGE_KEYS.USER_ID, String(res.data.userId))
          localStorage.removeItem(STORAGE_KEYS.TOKEN)
          setStatus('authenticated')
        } else {
          clearStoredAuth()
          setStatus('anonymous')
        }
      })
      .catch(() => {
        if (cancelled) return
        clearStoredAuth()
        setStatus('anonymous')
      })

    return () => {
      cancelled = true
    }
  }, [status])

  if (status === 'checking') {
    return <div className="auth-loading">正在恢复登录...</div>
  }

  if (status !== 'authenticated') {
    clearStoredAuth()
    return <Navigate to="/login" replace />
  }

  return children
}

function clearStoredAuth() {
  clearAccessToken()
  localStorage.removeItem(STORAGE_KEYS.TOKEN)
  localStorage.removeItem(STORAGE_KEYS.USER_ID)
  localStorage.removeItem(STORAGE_KEYS.ROLE)
}

function AuthenticatedWorkspace() {
  const location = useLocation()

  return (
    <>
      <InterpretationView />
      {location.pathname === '/history' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <HistoryView />
        </div>
      )}
      {location.pathname === '/terminology' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <TerminologyView />
        </div>
      )}
      {location.pathname === '/teams-bot' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <TeamsBotView />
        </div>
      )}
      {location.pathname === '/cost-analysis' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <CostAnalysisView />
        </div>
      )}
      {location.pathname === '/user-management' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <UserManagementView />
        </div>
      )}
      {location.pathname === '/account-security' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <AccountSecurityView />
        </div>
      )}
      {location.pathname === '/security-operations' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <SecurityOperationsView />
        </div>
      )}
    </>
  )
}

const App = () => {
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<LoginView />} />
        {/* P4 不可枚举的频道分享令牌链接 */}
        <Route path="/share/token/:token" element={<UserShareView />} />
        <Route path="*" element={<RequireAuth><AuthenticatedWorkspace /></RequireAuth>} />
      </Routes>
    </HashRouter>
  )
}

export default App
