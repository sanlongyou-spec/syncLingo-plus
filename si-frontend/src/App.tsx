/**
 * 应用根组件，路由配置
 */
import { HashRouter, Routes, Route } from 'react-router-dom'
import InterpretationView from './views/InterpretationView'
import VoiceCloneView from './views/VoiceCloneView'

const App = () => {
  return (
    <HashRouter>
      <Routes>
        <Route path="/" element={<InterpretationView />} />
        <Route path="/voice-clone" element={<VoiceCloneView />} />
        <Route path="*" element={<InterpretationView />} />
      </Routes>
    </HashRouter>
  )
}

export default App
