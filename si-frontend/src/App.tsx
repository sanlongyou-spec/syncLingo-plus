/**
 * 应用根组件，路由配置
 */
import { HashRouter, Routes, Route, useLocation } from 'react-router-dom'
import InterpretationView from './views/InterpretationView'
import LoginView from './views/LoginView'
import UserShareView from './views/UserShareView'
import HistoryView from './views/HistoryView'
import TerminologyView from './views/TerminologyView'
import TeamsBotView from './views/TeamsBotView'
import CostAnalysisView from './views/CostAnalysisView'
import { STORAGE_KEYS, ROUTES } from './constants'

function RequireAuth({ children }: { children: JSX.Element }) {
  const token = localStorage.getItem(STORAGE_KEYS.TOKEN)
  if (!token) {
    window.location.hash = ROUTES.LOGIN
    return null
  }
  return children
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
    </>
  )
}

const App = () => {
  return (
    <HashRouter>
      <Routes>
        <Route path="/login" element={<LoginView />} />
        <Route path="/share/user/:userId" element={<UserShareView />} />
        <Route path="*" element={<RequireAuth><AuthenticatedWorkspace /></RequireAuth>} />
      </Routes>
    </HashRouter>
  )
}

export default App
