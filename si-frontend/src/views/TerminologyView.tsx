import { ChangeEvent, FormEvent, useEffect, useMemo, useState } from 'react'
import {
  confirmHotwordsFromSession,
  createAsrHotword,
  createAsrHotwordsBatch,
  createHotwordsFromTerminology,
  createSystemUser,
  createTerminology,
  deleteAsrHotword,
  deleteSystemUser,
  deleteTerminology,
  getAsrHotwords,
  getSystemUsers,
  getTerminologies,
  getUserInterpretationSessions,
  importSystemUsers,
  importTerminology,
  previewHotwordsFromSession,
  updateAsrHotword,
  updateAsrHotwordEnabled,
  updateSystemUser,
  updateTerminology,
  updateTerminologyEnabled,
} from '../api'
import { ROUTES, STORAGE_KEYS } from '../constants'
import type { AsrHotword, HotwordSuggestion, InterpretationStatus, SystemUserInfo, Terminology } from '../types'
import './InterpretationView.css'
import './TerminologyView.css'

const EMPTY_TERM_FORM: Terminology = {
  termZh: '',
  termId: '',
  termEn: '',
  pinyin: '',
  category: '',
  note: '',
  reviewStatus: 'APPROVED',
  enabled: true,
}

const EMPTY_HOTWORD_FORM: AsrHotword = {
  phrase: '',
  language: 'zh-CN',
  category: '',
  weight: 1,
  sourceType: 'MANUAL',
  enabled: true,
}

const EMPTY_USER_FORM: SystemUserInfo = {
  department: '',
  personName: '',
  positionTitle: '',
  email: '',
  microsoftId: '',
  robinUid: '',
  teamsVerified: '',
  employmentStatus: '',
}

type Filter = 'all' | 'enabled' | 'disabled'
type Tab = 'terminology' | 'hotwords' | 'users'
type ExtractStep = 'pick' | 'preview'

function groupBy<T>(items: T[], key: (item: T) => string): [string, T[]][] {
  const map: Record<string, T[]> = {}
  for (const item of items) {
    const k = key(item).trim() || '未分类'
    if (!map[k]) map[k] = []
    map[k].push(item)
  }
  return Object.entries(map).sort(([a], [b]) => {
    if (a === '未分类') return 1
    if (b === '未分类') return -1
    return a.localeCompare(b, 'zh')
  })
}

export default function TerminologyView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const [activeTab, setActiveTab] = useState<Tab>('terminology')
  const [terms, setTerms] = useState<Terminology[]>([])
  const [hotwords, setHotwords] = useState<AsrHotword[]>([])
  const [users, setUsers] = useState<SystemUserInfo[]>([])
  const [keyword, setKeyword] = useState('')
  const [enabledFilter, setEnabledFilter] = useState<Filter>('all')
  const [languageFilter, setLanguageFilter] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [termForm, setTermForm] = useState<Terminology>(EMPTY_TERM_FORM)
  const [hotwordForm, setHotwordForm] = useState<AsrHotword>(EMPTY_HOTWORD_FORM)
  const [userForm, setUserForm] = useState<SystemUserInfo>(EMPTY_USER_FORM)
  const [bulkHotwords, setBulkHotwords] = useState('')
  const [editingTermId, setEditingTermId] = useState<number | null>(null)
  const [editingHotwordId, setEditingHotwordId] = useState<number | null>(null)
  const [editingUserId, setEditingUserId] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [userImporting, setUserImporting] = useState(false)
  const [userImportMessage, setUserImportMessage] = useState('')
  const [termImporting, setTermImporting] = useState(false)
  const [termImportMessage, setTermImportMessage] = useState('')

  const [collapsedTermGroups, setCollapsedTermGroups] = useState<Set<string>>(new Set())
  const [collapsedHwGroups, setCollapsedHwGroups] = useState<Set<string>>(new Set())

  const [extractModal, setExtractModal] = useState(false)
  const [extractStep, setExtractStep] = useState<ExtractStep>('pick')
  const [extractSessions, setExtractSessions] = useState<InterpretationStatus[]>([])
  const [extractSessionId, setExtractSessionId] = useState('')
  const [extractSuggestions, setExtractSuggestions] = useState<HotwordSuggestion[]>([])
  const [extractSelected, setExtractSelected] = useState<Set<string>>(new Set())
  const [extractLoading, setExtractLoading] = useState(false)

  const enabledParam = useMemo(() => {
    if (enabledFilter === 'enabled') return true
    if (enabledFilter === 'disabled') return false
    return undefined
  }, [enabledFilter])

  const groupedTerms = useMemo(
    () => groupBy(terms, t => t.category || ''),
    [terms]
  )
  const groupedHotwords = useMemo(
    () => groupBy(hotwords, h => h.category || ''),
    [hotwords]
  )

  const loadItems = async () => {
    setLoading(true)
    setError('')
    try {
      if (activeTab === 'terminology') {
        const res = await getTerminologies(userId, keyword, enabledParam)
        setTerms(res.data || [])
      } else if (activeTab === 'hotwords') {
        const res = await getAsrHotwords(userId, keyword, enabledParam, languageFilter, categoryFilter)
        setHotwords(res.data || [])
      } else {
        const res = await getSystemUsers(keyword)
        setUsers(res.data || [])
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : '加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void loadItems()
  }, [activeTab])

  const toggleTermGroup = (cat: string) =>
    setCollapsedTermGroups(prev => {
      const next = new Set(prev)
      next.has(cat) ? next.delete(cat) : next.add(cat)
      return next
    })

  const toggleHwGroup = (cat: string) =>
    setCollapsedHwGroups(prev => {
      const next = new Set(prev)
      next.has(cat) ? next.delete(cat) : next.add(cat)
      return next
    })

  const openExtractModal = async () => {
    setExtractModal(true)
    setExtractStep('pick')
    setExtractSessionId('')
    setExtractSuggestions([])
    setExtractSelected(new Set())
    setExtractLoading(true)
    try {
      const res = await getUserInterpretationSessions(userId)
      setExtractSessions((res.data || []).filter(s => s.status === 'STOPPED').slice(0, 20))
    } catch {
      setExtractSessions([])
    } finally {
      setExtractLoading(false)
    }
  }

  const previewExtract = async () => {
    if (!extractSessionId) return
    setExtractLoading(true)
    setError('')
    try {
      const res = await previewHotwordsFromSession(extractSessionId, userId)
      const suggestions = res.data || []
      setExtractSuggestions(suggestions)
      setExtractSelected(new Set(
        suggestions.filter(s => !s.exists).map(s => `${s.phrase}__${s.language}`)
      ))
      setExtractStep('preview')
    } catch (err) {
      setError(err instanceof Error ? err.message : '分析失败')
    } finally {
      setExtractLoading(false)
    }
  }

  const toggleExtractItem = (key: string) =>
    setExtractSelected(prev => {
      const next = new Set(prev)
      next.has(key) ? next.delete(key) : next.add(key)
      return next
    })

  const selectAllNew = () =>
    setExtractSelected(new Set(
      extractSuggestions.filter(s => !s.exists).map(s => `${s.phrase}__${s.language}`)
    ))

  const confirmExtract = async () => {
    const selected = extractSuggestions.filter(s => extractSelected.has(`${s.phrase}__${s.language}`))
    if (selected.length === 0) { setExtractModal(false); return }
    setExtractLoading(true)
    try {
      await confirmHotwordsFromSession(userId, selected)
      setExtractModal(false)
      setActiveTab('hotwords')
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '添加失败')
    } finally {
      setExtractLoading(false)
    }
  }

  const submitTerm = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      if (!termForm.termZh || (!termForm.termId && !termForm.termEn)) {
        throw new Error('至少填写中文，以及印尼语或英语中的一种')
      }
      if (editingTermId) {
        await updateTerminology(userId, editingTermId, termForm)
      } else {
        await createTerminology(userId, termForm)
      }
      setEditingTermId(null)
      setTermForm(EMPTY_TERM_FORM)
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存术语失败')
    } finally {
      setSaving(false)
    }
  }

  const submitHotword = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      if (!hotwordForm.phrase) throw new Error('请填写热词')
      if (editingHotwordId) {
        await updateAsrHotword(userId, editingHotwordId, hotwordForm)
      } else {
        await createAsrHotword(userId, hotwordForm)
      }
      setEditingHotwordId(null)
      setHotwordForm(EMPTY_HOTWORD_FORM)
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存热词失败')
    } finally {
      setSaving(false)
    }
  }

  const submitUser = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      if (!userForm.personName?.trim()) throw new Error('请填写姓名')
      if (!userForm.email?.trim()) throw new Error('请填写邮箱')
      if (editingUserId) {
        await updateSystemUser(editingUserId, userForm)
      } else {
        await createSystemUser(userForm)
      }
      setEditingUserId(null)
      setUserForm(EMPTY_USER_FORM)
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存用户信息失败')
    } finally {
      setSaving(false)
    }
  }

  const importUserExcel = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setUserImporting(true)
    setUserImportMessage('')
    setError('')
    try {
      const res = await importSystemUsers(file)
      const data = res.data
      setUserImportMessage(
        data
          ? `已导入 ${data.totalCount} 条，新增 ${data.createdCount} 条，更新 ${data.updatedCount} 条，跳过 ${data.skippedCount} 条`
          : '导入完成'
      )
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入用户信息失败')
    } finally {
      setUserImporting(false)
    }
  }

  const importTermExcel = async (event: ChangeEvent<HTMLInputElement>) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    setTermImporting(true)
    setTermImportMessage('')
    setError('')
    try {
      const res = await importTerminology(userId, file)
      const data = res.data
      setTermImportMessage(
        data ? `已导入 ${data.createdCount} 条，跳过重复 ${data.skippedCount} 条` : '导入完成'
      )
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '导入术语失败')
    } finally {
      setTermImporting(false)
    }
  }

  const submitBulkHotwords = async () => {
    const rows = bulkHotwords.split(/\r?\n/).map(v => v.trim()).filter(Boolean)
    if (rows.length === 0) return
    await createAsrHotwordsBatch(userId, rows.map(phrase => ({
      phrase,
      language: hotwordForm.language,
      category: hotwordForm.category,
      weight: hotwordForm.weight,
      enabled: true,
    })))
    setBulkHotwords('')
    await loadItems()
  }

  const editTerm = (item: Terminology) => {
    setEditingTermId(item.id || null)
    setTermForm({ ...EMPTY_TERM_FORM, ...item, reviewStatus: item.reviewStatus || 'APPROVED', enabled: item.enabled !== false })
  }

  const editHotword = (item: AsrHotword) => {
    setEditingHotwordId(item.id || null)
    setHotwordForm({ ...EMPTY_HOTWORD_FORM, ...item, enabled: item.enabled !== false, weight: item.weight ?? 1 })
  }

  const editUser = (item: SystemUserInfo) => {
    setEditingUserId(item.id || null)
    setUserForm({ ...EMPTY_USER_FORM, ...item })
  }

  const removeTerm = async (item: Terminology) => {
    if (!item.id || !window.confirm('删除这个术语？')) return
    await deleteTerminology(userId, item.id)
    await loadItems()
  }

  const removeHotword = async (item: AsrHotword) => {
    if (!item.id || !window.confirm('删除这个热词？')) return
    await deleteAsrHotword(userId, item.id)
    await loadItems()
  }

  const removeUser = async (item: SystemUserInfo) => {
    if (!item.id || !window.confirm('删除这个用户信息？')) return
    await deleteSystemUser(item.id)
    await loadItems()
  }

  const toggleTermEnabled = async (item: Terminology) => {
    if (!item.id) return
    await updateTerminologyEnabled(userId, item.id, item.enabled === false)
    await loadItems()
  }

  const toggleHotwordEnabled = async (item: AsrHotword) => {
    if (!item.id) return
    await updateAsrHotwordEnabled(userId, item.id, item.enabled === false)
    await loadItems()
  }

  const addAsHotwords = async (item: Terminology) => {
    if (!item.id) return
    await createHotwordsFromTerminology(userId, item.id)
    setActiveTab('hotwords')
  }

  const LANG_LABEL: Record<string, string> = { 'zh-CN': '中文', 'id-ID': '印尼语', 'en-US': '英语' }
  const SOURCE_LABEL: Record<string, string> = { TERMINOLOGY: '术语', MANUAL: '手工', AUTO_EXTRACTED: '自动' }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">配置</h1>
          <span className="si-brand-sub">术语、ASR 热词和系统用户信息</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>返回同传</button>
        </div>
      </header>

      <div className="asset-tabs">
        <button className={activeTab === 'terminology' ? 'is-active' : ''} onClick={() => setActiveTab('terminology')}>术语</button>
        <button className={activeTab === 'hotwords' ? 'is-active' : ''} onClick={() => setActiveTab('hotwords')}>ASR 热词</button>
        <button className={activeTab === 'users' ? 'is-active' : ''} onClick={() => setActiveTab('users')}>用户信息</button>
      </div>

      <main className="terminology-main">
        {/* ── 左侧编辑面板 ── */}
        {activeTab === 'terminology' && (
          <section className="terminology-editor">
            <h2>{editingTermId ? '编辑术语' : '新增术语'}</h2>
            <form onSubmit={submitTerm}>
              <label>中文<input value={termForm.termZh || ''} onChange={e => setTermForm(p => ({ ...p, termZh: e.target.value }))} /></label>
              <label>印尼语<input value={termForm.termId || ''} onChange={e => setTermForm(p => ({ ...p, termId: e.target.value }))} /></label>
              <label>英语<input value={termForm.termEn || ''} onChange={e => setTermForm(p => ({ ...p, termEn: e.target.value }))} /></label>
              <label>拼音<input value={termForm.pinyin || ''} onChange={e => setTermForm(p => ({ ...p, pinyin: e.target.value }))} /></label>
              <label>分类<input value={termForm.category || ''} onChange={e => setTermForm(p => ({ ...p, category: e.target.value }))} placeholder="如：人名、地名、专业术语" /></label>
              <label>审核状态
                <select value={termForm.reviewStatus || 'APPROVED'} onChange={e => setTermForm(p => ({ ...p, reviewStatus: e.target.value }))}>
                  <option value="APPROVED">已审核</option>
                  <option value="NEED_REVIEW">待审核</option>
                </select>
              </label>
              <label className="terminology-editor-note">备注<textarea value={termForm.note || ''} onChange={e => setTermForm(p => ({ ...p, note: e.target.value }))} /></label>
              <label className="terminology-toggle"><input type="checkbox" checked={termForm.enabled !== false} onChange={e => setTermForm(p => ({ ...p, enabled: e.target.checked }))} />启用</label>
              <div className="terminology-editor-actions">
                <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button>
                {editingTermId && <button type="button" onClick={() => { setEditingTermId(null); setTermForm(EMPTY_TERM_FORM) }}>取消</button>}
              </div>
            </form>
            <div className="user-import-panel">
              <label className="user-import-button">
                <input type="file" accept=".xlsx,.xls" disabled={termImporting} onChange={importTermExcel} />
                {termImporting ? '导入中...' : '批量导入术语表格'}
              </label>
              <div className="user-import-hint">表头需含「中文/印尼语/英语」(可含拼音/分类/备注)，重复项自动跳过</div>
              {termImportMessage && <div className="user-import-message">{termImportMessage}</div>}
            </div>
          </section>
        )}

        {activeTab === 'hotwords' && (
          <section className="terminology-editor">
            <h2>{editingHotwordId ? '编辑热词' : '新增热词'}</h2>
            <form onSubmit={submitHotword}>
              <label>热词<input value={hotwordForm.phrase || ''} onChange={e => setHotwordForm(p => ({ ...p, phrase: e.target.value }))} /></label>
              <label>语种
                <select value={hotwordForm.language || ''} onChange={e => setHotwordForm(p => ({ ...p, language: e.target.value }))}>
                  <option value="zh-CN">中文</option><option value="id-ID">印尼语</option><option value="en-US">英语</option>
                </select>
              </label>
              <label>分类<input value={hotwordForm.category || ''} onChange={e => setHotwordForm(p => ({ ...p, category: e.target.value }))} placeholder="如：人名、地名、专业术语" /></label>
              <label>权重<input type="number" min="0.1" max="2" step="0.1" value={hotwordForm.weight ?? 1} onChange={e => setHotwordForm(p => ({ ...p, weight: Number(e.target.value) }))} /></label>
              <label className="terminology-toggle"><input type="checkbox" checked={hotwordForm.enabled !== false} onChange={e => setHotwordForm(p => ({ ...p, enabled: e.target.checked }))} />启用</label>
              <div className="terminology-editor-actions">
                <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button>
                {editingHotwordId && <button type="button" onClick={() => { setEditingHotwordId(null); setHotwordForm(EMPTY_HOTWORD_FORM) }}>取消</button>}
              </div>
              <label className="terminology-editor-note">批量新增<textarea value={bulkHotwords} onChange={e => setBulkHotwords(e.target.value)} placeholder="每行一个热词" /></label>
              <div className="terminology-editor-actions"><button type="button" onClick={() => { void submitBulkHotwords() }}>批量导入</button></div>
            </form>
          </section>
        )}

        {activeTab === 'users' && (
          <section className="terminology-editor">
            <h2>{editingUserId ? '编辑用户信息' : '新增用户信息'}</h2>
            <form onSubmit={submitUser}>
              <label>姓名<input value={userForm.personName || ''} onChange={e => setUserForm(p => ({ ...p, personName: e.target.value }))} /></label>
              <label>邮箱<input type="email" value={userForm.email || ''} onChange={e => setUserForm(p => ({ ...p, email: e.target.value }))} /></label>
              <label>部门<input value={userForm.department || ''} onChange={e => setUserForm(p => ({ ...p, department: e.target.value }))} /></label>
              <label>职位<input value={userForm.positionTitle || ''} onChange={e => setUserForm(p => ({ ...p, positionTitle: e.target.value }))} /></label>
              <label>Microsoft ID<input value={userForm.microsoftId || ''} onChange={e => setUserForm(p => ({ ...p, microsoftId: e.target.value }))} /></label>
              <label>Robin UID<input value={userForm.robinUid || ''} onChange={e => setUserForm(p => ({ ...p, robinUid: e.target.value }))} /></label>
              <label>Teams 验证<input value={userForm.teamsVerified || ''} onChange={e => setUserForm(p => ({ ...p, teamsVerified: e.target.value }))} /></label>
              <label>在职状态<input value={userForm.employmentStatus || ''} onChange={e => setUserForm(p => ({ ...p, employmentStatus: e.target.value }))} /></label>
              <div className="terminology-editor-actions">
                <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button>
                {editingUserId && <button type="button" onClick={() => { setEditingUserId(null); setUserForm(EMPTY_USER_FORM) }}>取消</button>}
              </div>
            </form>
            <div className="user-import-panel">
              <label className="user-import-button">
                <input type="file" accept=".xlsx,.xls" disabled={userImporting} onChange={importUserExcel} />
                {userImporting ? '导入中...' : '上传用户表格'}
              </label>
              {userImportMessage && <div className="user-import-message">{userImportMessage}</div>}
            </div>
          </section>
        )}

        {/* ── 右侧列表面板 ── */}
        <section className="terminology-table-panel">
          <form className="terminology-toolbar" onSubmit={e => { e.preventDefault(); void loadItems() }}>
            <input
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder={activeTab === 'terminology' ? '搜索术语或分类' : activeTab === 'hotwords' ? '搜索热词或分类' : '搜索姓名、邮箱、部门或职位'}
            />
            {activeTab !== 'users' && (
              <select value={enabledFilter} onChange={e => setEnabledFilter(e.target.value as Filter)}>
                <option value="all">全部</option><option value="enabled">启用</option><option value="disabled">停用</option>
              </select>
            )}
            {activeTab === 'hotwords' && (
              <>
                <select value={languageFilter} onChange={e => setLanguageFilter(e.target.value)}>
                  <option value="">全部语种</option><option value="zh-CN">中文</option><option value="id-ID">印尼语</option><option value="en-US">英语</option>
                </select>
                <input value={categoryFilter} onChange={e => setCategoryFilter(e.target.value)} placeholder="分类" style={{ maxWidth: 80 }} />
              </>
            )}
            <button type="submit">查询</button>
            {activeTab === 'hotwords' && (
              <button type="button" className="extract-btn" onClick={() => { void openExtractModal() }}>从历史提取热词</button>
            )}
          </form>
          {error && <div className="terminology-error">{error}</div>}

          <div className="terminology-table-wrap">
            {activeTab === 'terminology' && (
              <table>
                <thead><tr><th>中文</th><th>印尼语</th><th>英语</th><th>分类</th><th>状态</th><th>来源</th><th>操作</th></tr></thead>
                <tbody>
                  {loading && <tr><td colSpan={7}>加载中...</td></tr>}
                  {!loading && terms.length === 0 && <tr><td colSpan={7}>暂无术语</td></tr>}
                  {!loading && groupedTerms.map(([cat, items]) => (
                    <>
                      <tr key={`g-${cat}`} className="term-group-header" onClick={() => toggleTermGroup(cat)}>
                        <td colSpan={7}>
                          <span className="term-group-toggle">{collapsedTermGroups.has(cat) ? '▶' : '▼'}</span>
                          <span className="term-group-name">{cat}</span>
                          <span className="term-group-count">{items.length} 条</span>
                        </td>
                      </tr>
                      {!collapsedTermGroups.has(cat) && items.map(item => (
                        <tr key={item.id}>
                          <td>{item.termZh || '-'}</td><td>{item.termId || '-'}</td><td>{item.termEn || '-'}</td>
                          <td>{item.category || '-'}</td>
                          <td><span className={item.enabled === false ? 'is-disabled' : 'is-enabled'}>{item.enabled === false ? '停用' : '启用'}</span>{item.reviewStatus === 'NEED_REVIEW' ? ' / 待审核' : ''}</td>
                          <td>{item.sourceSheet ? `${item.sourceSheet}:${item.sourceRow || ''}` : '手工'}</td>
                          <td>
                            <button onClick={() => editTerm(item)}>编辑</button>
                            <button onClick={() => { void addAsHotwords(item) }}>加入热词</button>
                            <button onClick={() => { void toggleTermEnabled(item) }}>{item.enabled === false ? '启用' : '停用'}</button>
                            <button onClick={() => { void removeTerm(item) }}>删除</button>
                          </td>
                        </tr>
                      ))}
                    </>
                  ))}
                </tbody>
              </table>
            )}

            {activeTab === 'hotwords' && (
              <table>
                <thead><tr><th>热词</th><th>语种</th><th>分类</th><th>权重</th><th>来源</th><th>最近使用</th><th>状态</th><th>操作</th></tr></thead>
                <tbody>
                  {loading && <tr><td colSpan={8}>加载中...</td></tr>}
                  {!loading && hotwords.length === 0 && <tr><td colSpan={8}>暂无热词</td></tr>}
                  {!loading && groupedHotwords.map(([cat, items]) => (
                    <>
                      <tr key={`g-${cat}`} className="term-group-header" onClick={() => toggleHwGroup(cat)}>
                        <td colSpan={8}>
                          <span className="term-group-toggle">{collapsedHwGroups.has(cat) ? '▶' : '▼'}</span>
                          <span className="term-group-name">{cat}</span>
                          <span className="term-group-count">{items.length} 条</span>
                        </td>
                      </tr>
                      {!collapsedHwGroups.has(cat) && items.map(item => (
                        <tr key={item.id}>
                          <td>{item.phrase || '-'}</td>
                          <td>{LANG_LABEL[item.language || ''] || item.language || '-'}</td>
                          <td>{item.category || '-'}</td>
                          <td>{item.weight ?? 1}</td>
                          <td>{SOURCE_LABEL[item.sourceType || ''] || item.sourceType || '-'}</td>
                          <td>{item.lastUsedTime ? item.lastUsedTime.replace('T', ' ').slice(0, 16) : '-'}</td>
                          <td><span className={item.enabled === false ? 'is-disabled' : 'is-enabled'}>{item.enabled === false ? '停用' : '启用'}</span></td>
                          <td>
                            <button onClick={() => editHotword(item)}>编辑</button>
                            <button onClick={() => { void toggleHotwordEnabled(item) }}>{item.enabled === false ? '启用' : '停用'}</button>
                            <button onClick={() => { void removeHotword(item) }}>删除</button>
                          </td>
                        </tr>
                      ))}
                    </>
                  ))}
                </tbody>
              </table>
            )}

            {activeTab === 'users' && (
              <table>
                <thead>
                  <tr>
                    <th>姓名</th><th>邮箱</th><th>部门</th><th>职位</th><th>Microsoft ID</th><th>Robin UID</th><th>Teams 验证</th><th>在职状态</th><th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  {loading && <tr><td colSpan={9}>加载中...</td></tr>}
                  {!loading && users.length === 0 && <tr><td colSpan={9}>暂无用户信息</td></tr>}
                  {!loading && users.map(item => (
                    <tr key={item.id}>
                      <td>{item.personName || '-'}</td>
                      <td>{item.email || '-'}</td>
                      <td>{item.department || '-'}</td>
                      <td>{item.positionTitle || '-'}</td>
                      <td>{item.microsoftId || '-'}</td>
                      <td>{item.robinUid || '-'}</td>
                      <td>{item.teamsVerified || '-'}</td>
                      <td>{item.employmentStatus || '-'}</td>
                      <td>
                        <button onClick={() => editUser(item)}>编辑</button>
                        <button onClick={() => { void removeUser(item) }}>删除</button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      </main>

      {/* ── 热词提取模态框 ── */}
      {extractModal && (
        <div className="extract-modal-overlay" onClick={() => setExtractModal(false)}>
          <div className="extract-modal" onClick={e => e.stopPropagation()}>
            <div className="extract-modal-header">
              <span className="extract-modal-title">从历史会话提取热词</span>
              <button className="extract-modal-close" onClick={() => setExtractModal(false)}>✕</button>
            </div>

            {extractStep === 'pick' && (
              <div className="extract-modal-body">
                <p className="extract-hint">选择一个已结束的会话，AI 将从会议文本中识别人名、地名、组织名和专业术语。</p>
                {extractLoading ? (
                  <div className="extract-loading">加载会话列表...</div>
                ) : extractSessions.length === 0 ? (
                  <div className="extract-loading">暂无已结束的会话</div>
                ) : (
                  <select className="extract-session-select" value={extractSessionId} onChange={e => setExtractSessionId(e.target.value)}>
                    <option value="">-- 选择会话 --</option>
                    {extractSessions.map(s => (
                      <option key={s.sessionId} value={s.sessionId}>
                        {s.title || '未命名同传'} · {s.startTime ? new Date(s.startTime).toLocaleDateString() : ''}
                      </option>
                    ))}
                  </select>
                )}
                <div className="extract-modal-actions">
                  <button onClick={() => setExtractModal(false)}>取消</button>
                  <button className="extract-btn-primary" disabled={!extractSessionId || extractLoading} onClick={() => { void previewExtract() }}>
                    {extractLoading ? '分析中...' : '开始分析'}
                  </button>
                </div>
              </div>
            )}

            {extractStep === 'preview' && (
              <div className="extract-modal-body">
                <div className="extract-preview-toolbar">
                  <span className="extract-hint">共识别 {extractSuggestions.length} 个词汇，选择要添加的项目：</span>
                  <button onClick={selectAllNew}>仅选新词</button>
                </div>
                <div className="extract-suggestion-list">
                  {extractSuggestions.length === 0 && (
                    <div className="extract-loading">未识别到有效词汇</div>
                  )}
                  {extractSuggestions.map(s => {
                    const key = `${s.phrase}__${s.language}`
                    return (
                      <label key={key} className="extract-suggestion-item">
                        <input
                          type="checkbox"
                          checked={extractSelected.has(key)}
                          disabled={!!s.exists}
                          onChange={() => toggleExtractItem(key)}
                        />
                        <span className="extract-phrase">{s.phrase}</span>
                        <span className="extract-cat">{s.category}</span>
                        <span className="extract-lang">{LANG_LABEL[s.language] || s.language}</span>
                        {s.exists
                          ? <span className="badge-exists">已存在</span>
                          : <span className="badge-new">新增</span>}
                      </label>
                    )
                  })}
                </div>
                <div className="extract-modal-actions">
                  <button onClick={() => setExtractStep('pick')}>重新选择</button>
                  <button className="extract-btn-primary" disabled={extractSelected.size === 0 || extractLoading} onClick={() => { void confirmExtract() }}>
                    {extractLoading ? '添加中...' : `确认添加 ${extractSelected.size} 个热词`}
                  </button>
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  )
}
