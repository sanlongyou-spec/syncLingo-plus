import { useState } from 'react'
import { logout } from '../api'
import { ROUTES } from '../constants'
import SecurityOperationsView from './SecurityOperationsView'
import UserManagementView from './UserManagementView'
import './AdminConsoleView.css'

type AdminTab = 'users' | 'security'

export default function AdminConsoleView() {
  const [activeTab, setActiveTab] = useState<AdminTab>('users')
  const [logoutBusy, setLogoutBusy] = useState(false)

  const handleLogout = async () => {
    setLogoutBusy(true)
    try {
      await logout()
    } finally {
      setLogoutBusy(false)
      window.location.hash = ROUTES.LOGIN
    }
  }

  return (
    <div className="si-root admin-console">
      <header className="si-topbar admin-console-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">管理控制台</h1>
          <span className="si-brand-sub">用户管理与安全运维</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" type="button" onClick={handleLogout} disabled={logoutBusy}>
            {logoutBusy ? '退出中...' : '退出登录'}
          </button>
        </div>
      </header>

      <nav className="admin-console-tabs" aria-label="管理功能">
        <button
          type="button"
          className={activeTab === 'users' ? 'is-active' : ''}
          onClick={() => setActiveTab('users')}
        >
          用户管理
        </button>
        <button
          type="button"
          className={activeTab === 'security' ? 'is-active' : ''}
          onClick={() => setActiveTab('security')}
        >
          安全运维
        </button>
      </nav>

      <section className="admin-console-content">
        {activeTab === 'users' ? (
          <UserManagementView embedded />
        ) : (
          <SecurityOperationsView embedded />
        )}
      </section>
    </div>
  )
}
