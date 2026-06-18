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
import MeetingsView from './views/MeetingsView'
import CostAnalysisView from './views/CostAnalysisView'
import AdminConsoleView from './views/AdminConsoleView'
import { STORAGE_KEYS } from './constants'
import { getMe, refreshAuth } from './api'
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
  const [currentRole, setCurrentRole] = useState<string>(localStorage.getItem(STORAGE_KEYS.ROLE) || '')
  const [roleChecked, setRoleChecked] = useState(Boolean(localStorage.getItem(STORAGE_KEYS.ROLE)))

  useEffect(() => {
    let cancelled = false
    getMe()
      .then(res => {
        if (cancelled) return
        const role = res.code === 200 ? (res.data?.role || '') : ''
        setCurrentRole(role)
        if (role) {
          localStorage.setItem(STORAGE_KEYS.ROLE, role)
        }
      })
      .finally(() => {
        if (!cancelled) setRoleChecked(true)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const role = currentRole.toUpperCase()
  const adminOnlyPaths = new Set(['/admin', '/user-management', '/security-operations'])

  if (!roleChecked && !role) {
    return <div className="auth-loading">正在读取账号权限...</div>
  }

  if (role === 'ADMIN') {
    if (location.pathname !== '/admin') {
      return <Navigate to="/admin" replace />
    }
    return <AdminConsoleView />
  }

  if (adminOnlyPaths.has(location.pathname)) {
    return <Navigate to="/" replace />
  }

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
      {location.pathname === '/meetings' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <MeetingsView />
        </div>
      )}
      {location.pathname === '/cost-analysis' && (
        <div className="route-overlay" role="dialog" aria-modal="true">
          <CostAnalysisView />
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
