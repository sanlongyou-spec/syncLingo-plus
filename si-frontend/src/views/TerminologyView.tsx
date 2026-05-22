import { FormEvent, useEffect, useMemo, useState } from 'react'
import {
  createAsrHotword,
  createAsrHotwordsBatch,
  createHotwordsFromTerminology,
  createTerminology,
  deleteAsrHotword,
  deleteTerminology,
  deleteUserGlossary,
  getAsrHotwords,
  getTerminologies,
  getUserGlossaries,
  saveUserGlossary,
  updateAsrHotword,
  updateAsrHotwordEnabled,
  updateTerminology,
  updateTerminologyEnabled,
} from '../api'
import { ROUTES, STORAGE_KEYS } from '../constants'
import type { AsrHotword, Terminology, UserGlossaryConfig } from '../types'
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

const EMPTY_GLOSSARY_FORM: UserGlossaryConfig = {
  sourceLang: 'zh-CN',
  targetLang: 'id',
  glossaryId: '',
  enabled: true,
}

type Filter = 'all' | 'enabled' | 'disabled'
type Tab = 'terminology' | 'hotwords' | 'glossaries'

export default function TerminologyView() {
  const userId = Number(localStorage.getItem(STORAGE_KEYS.USER_ID) || '1')
  const [activeTab, setActiveTab] = useState<Tab>('terminology')
  const [terms, setTerms] = useState<Terminology[]>([])
  const [hotwords, setHotwords] = useState<AsrHotword[]>([])
  const [glossaries, setGlossaries] = useState<UserGlossaryConfig[]>([])
  const [keyword, setKeyword] = useState('')
  const [enabledFilter, setEnabledFilter] = useState<Filter>('all')
  const [languageFilter, setLanguageFilter] = useState('')
  const [categoryFilter, setCategoryFilter] = useState('')
  const [termForm, setTermForm] = useState<Terminology>(EMPTY_TERM_FORM)
  const [hotwordForm, setHotwordForm] = useState<AsrHotword>(EMPTY_HOTWORD_FORM)
  const [glossaryForm, setGlossaryForm] = useState<UserGlossaryConfig>(EMPTY_GLOSSARY_FORM)
  const [bulkHotwords, setBulkHotwords] = useState('')
  const [editingTermId, setEditingTermId] = useState<number | null>(null)
  const [editingHotwordId, setEditingHotwordId] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  const enabledParam = useMemo(() => {
    if (enabledFilter === 'enabled') return true
    if (enabledFilter === 'disabled') return false
    return undefined
  }, [enabledFilter])

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
        const res = await getUserGlossaries(userId)
        setGlossaries(res.data || [])
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

  const submitGlossary = async (event: FormEvent) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    try {
      if (!glossaryForm.glossaryId) throw new Error('请填写 glossaryId')
      await saveUserGlossary(userId, glossaryForm)
      setGlossaryForm(EMPTY_GLOSSARY_FORM)
      await loadItems()
    } catch (err) {
      setError(err instanceof Error ? err.message : '保存 glossary 失败')
    } finally {
      setSaving(false)
    }
  }

  const submitBulkHotwords = async () => {
    const rows = bulkHotwords.split(/\r?\n/).map(value => value.trim()).filter(Boolean)
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

  const removeGlossary = async (item: UserGlossaryConfig) => {
    if (!item.id || !window.confirm('删除这个 glossary 配置？')) return
    await deleteUserGlossary(userId, item.id)
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

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">语言资产</h1>
          <span className="si-brand-sub">术语、ASR 热词和 glossary 按当前用户隔离</span>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>返回同传</button>
        </div>
      </header>

      <div className="asset-tabs">
        <button className={activeTab === 'terminology' ? 'is-active' : ''} onClick={() => setActiveTab('terminology')}>术语</button>
        <button className={activeTab === 'hotwords' ? 'is-active' : ''} onClick={() => setActiveTab('hotwords')}>ASR 热词</button>
        <button className={activeTab === 'glossaries' ? 'is-active' : ''} onClick={() => setActiveTab('glossaries')}>Glossary</button>
      </div>

      <main className="terminology-main">
        {activeTab === 'terminology' && (
          <section className="terminology-editor">
            <h2>{editingTermId ? '编辑术语' : '新增术语'}</h2>
            <form onSubmit={submitTerm}>
              <label>中文<input value={termForm.termZh || ''} onChange={event => setTermForm(prev => ({ ...prev, termZh: event.target.value }))} /></label>
              <label>印尼语<input value={termForm.termId || ''} onChange={event => setTermForm(prev => ({ ...prev, termId: event.target.value }))} /></label>
              <label>英语<input value={termForm.termEn || ''} onChange={event => setTermForm(prev => ({ ...prev, termEn: event.target.value }))} /></label>
              <label>拼音<input value={termForm.pinyin || ''} onChange={event => setTermForm(prev => ({ ...prev, pinyin: event.target.value }))} /></label>
              <label>分类<input value={termForm.category || ''} onChange={event => setTermForm(prev => ({ ...prev, category: event.target.value }))} /></label>
              <label>审核状态
                <select value={termForm.reviewStatus || 'APPROVED'} onChange={event => setTermForm(prev => ({ ...prev, reviewStatus: event.target.value }))}>
                  <option value="APPROVED">已审核</option>
                  <option value="NEED_REVIEW">待审核</option>
                </select>
              </label>
              <label className="terminology-editor-note">备注<textarea value={termForm.note || ''} onChange={event => setTermForm(prev => ({ ...prev, note: event.target.value }))} /></label>
              <label className="terminology-toggle"><input type="checkbox" checked={termForm.enabled !== false} onChange={event => setTermForm(prev => ({ ...prev, enabled: event.target.checked }))} />启用</label>
              <div className="terminology-editor-actions">
                <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button>
                {editingTermId && <button type="button" onClick={() => { setEditingTermId(null); setTermForm(EMPTY_TERM_FORM) }}>取消</button>}
              </div>
            </form>
          </section>
        )}

        {activeTab === 'hotwords' && (
          <section className="terminology-editor">
            <h2>{editingHotwordId ? '编辑热词' : '新增热词'}</h2>
            <form onSubmit={submitHotword}>
              <label>热词<input value={hotwordForm.phrase || ''} onChange={event => setHotwordForm(prev => ({ ...prev, phrase: event.target.value }))} /></label>
              <label>语种
                <select value={hotwordForm.language || ''} onChange={event => setHotwordForm(prev => ({ ...prev, language: event.target.value }))}>
                  <option value="zh-CN">中文</option><option value="id-ID">印尼语</option><option value="en-US">英语</option>
                </select>
              </label>
              <label>分类<input value={hotwordForm.category || ''} onChange={event => setHotwordForm(prev => ({ ...prev, category: event.target.value }))} /></label>
              <label>权重<input type="number" min="0.1" max="2" step="0.1" value={hotwordForm.weight ?? 1} onChange={event => setHotwordForm(prev => ({ ...prev, weight: Number(event.target.value) }))} /></label>
              <label className="terminology-toggle"><input type="checkbox" checked={hotwordForm.enabled !== false} onChange={event => setHotwordForm(prev => ({ ...prev, enabled: event.target.checked }))} />启用</label>
              <div className="terminology-editor-actions">
                <button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button>
                {editingHotwordId && <button type="button" onClick={() => { setEditingHotwordId(null); setHotwordForm(EMPTY_HOTWORD_FORM) }}>取消</button>}
              </div>
              <label className="terminology-editor-note">批量新增<textarea value={bulkHotwords} onChange={event => setBulkHotwords(event.target.value)} placeholder="每行一个热词" /></label>
              <div className="terminology-editor-actions"><button type="button" onClick={() => { void submitBulkHotwords() }}>批量导入</button></div>
            </form>
          </section>
        )}

        {activeTab === 'glossaries' && (
          <section className="terminology-editor">
            <h2>用户 Glossary</h2>
            <form onSubmit={submitGlossary}>
              <label>源语种
                <select value={glossaryForm.sourceLang || 'zh-CN'} onChange={event => setGlossaryForm(prev => ({ ...prev, sourceLang: event.target.value }))}>
                  <option value="zh-CN">中文</option><option value="id">印尼语</option><option value="en">英语</option>
                </select>
              </label>
              <label>目标语种
                <select value={glossaryForm.targetLang || 'id'} onChange={event => setGlossaryForm(prev => ({ ...prev, targetLang: event.target.value }))}>
                  <option value="zh-CN">中文</option><option value="id">印尼语</option><option value="en">英语</option>
                </select>
              </label>
              <label>Glossary ID<input value={glossaryForm.glossaryId || ''} onChange={event => setGlossaryForm(prev => ({ ...prev, glossaryId: event.target.value }))} /></label>
              <label className="terminology-toggle"><input type="checkbox" checked={glossaryForm.enabled !== false} onChange={event => setGlossaryForm(prev => ({ ...prev, enabled: event.target.checked }))} />启用</label>
              <div className="terminology-editor-actions"><button type="submit" disabled={saving}>{saving ? '保存中...' : '保存'}</button></div>
            </form>
          </section>
        )}

        <section className="terminology-table-panel">
          {activeTab !== 'glossaries' && (
            <form className="terminology-toolbar" onSubmit={event => { event.preventDefault(); void loadItems() }}>
              <input value={keyword} onChange={event => setKeyword(event.target.value)} placeholder={activeTab === 'terminology' ? '搜索术语或分类' : '搜索热词或分类'} />
              <select value={enabledFilter} onChange={event => setEnabledFilter(event.target.value as Filter)}>
                <option value="all">全部</option><option value="enabled">启用</option><option value="disabled">停用</option>
              </select>
              {activeTab === 'hotwords' && (
                <>
                  <select value={languageFilter} onChange={event => setLanguageFilter(event.target.value)}>
                    <option value="">全部语种</option><option value="zh-CN">中文</option><option value="id-ID">印尼语</option><option value="en-US">英语</option>
                  </select>
                  <input value={categoryFilter} onChange={event => setCategoryFilter(event.target.value)} placeholder="分类" />
                </>
              )}
              <button type="submit">查询</button>
            </form>
          )}
          {error && <div className="terminology-error">{error}</div>}
          <div className="terminology-table-wrap">
            {activeTab === 'terminology' && (
              <table>
                <thead><tr><th>中文</th><th>印尼语</th><th>英语</th><th>分类</th><th>状态</th><th>来源</th><th>操作</th></tr></thead>
                <tbody>
                  {loading && <tr><td colSpan={7}>加载中...</td></tr>}
                  {!loading && terms.length === 0 && <tr><td colSpan={7}>暂无术语</td></tr>}
                  {terms.map(item => (
                    <tr key={item.id}>
                      <td>{item.termZh || '-'}</td><td>{item.termId || '-'}</td><td>{item.termEn || '-'}</td><td>{item.category || '-'}</td>
                      <td><span className={item.enabled === false ? 'is-disabled' : 'is-enabled'}>{item.enabled === false ? '停用' : '启用'}</span>{item.reviewStatus === 'NEED_REVIEW' ? ' / 待审核' : ''}</td>
                      <td>{item.sourceSheet ? `${item.sourceSheet}:${item.sourceRow || ''}` : '手工'}</td>
                      <td><button onClick={() => editTerm(item)}>编辑</button><button onClick={() => { void addAsHotwords(item) }}>加入热词</button><button onClick={() => { void toggleTermEnabled(item) }}>{item.enabled === false ? '启用' : '停用'}</button><button onClick={() => { void removeTerm(item) }}>删除</button></td>
                    </tr>
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
                  {hotwords.map(item => (
                    <tr key={item.id}>
                      <td>{item.phrase || '-'}</td><td>{item.language || '-'}</td><td>{item.category || '-'}</td><td>{item.weight ?? 1}</td>
                      <td>{item.sourceType === 'TERMINOLOGY' ? '术语' : '手工'}</td><td>{item.lastUsedTime ? item.lastUsedTime.replace('T', ' ') : '-'}</td>
                      <td><span className={item.enabled === false ? 'is-disabled' : 'is-enabled'}>{item.enabled === false ? '停用' : '启用'}</span></td>
                      <td><button onClick={() => editHotword(item)}>编辑</button><button onClick={() => { void toggleHotwordEnabled(item) }}>{item.enabled === false ? '启用' : '停用'}</button><button onClick={() => { void removeHotword(item) }}>删除</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
            {activeTab === 'glossaries' && (
              <table>
                <thead><tr><th>源语种</th><th>目标语种</th><th>Glossary ID</th><th>状态</th><th>操作</th></tr></thead>
                <tbody>
                  {loading && <tr><td colSpan={5}>加载中...</td></tr>}
                  {!loading && glossaries.length === 0 && <tr><td colSpan={5}>暂无 glossary 配置</td></tr>}
                  {glossaries.map(item => (
                    <tr key={item.id}>
                      <td>{item.sourceLang}</td><td>{item.targetLang}</td><td>{item.glossaryId}</td>
                      <td><span className={item.enabled === false ? 'is-disabled' : 'is-enabled'}>{item.enabled === false ? '停用' : '启用'}</span></td>
                      <td><button onClick={() => { void removeGlossary(item) }}>删除</button></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </section>
      </main>
    </div>
  )
}
