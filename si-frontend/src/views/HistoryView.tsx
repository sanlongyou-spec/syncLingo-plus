import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import {
  deleteInterpretationSession,
  deleteMeeting,
  extractActionItems,
  getActionItems,
  getMeetings,
  getMeetingSessions,
  getSystemUsers,
  getMeetingSummary,
  getPublicInterpretationResults,
  getSessionSpeakerIdentities,
  getSpeakerSummaries,
  mapSessionSpeakerIdentity,
  regenerateMeetingSummary,
  regenerateSpeakerSummary,
  sendTeamsSummaryToUsers,
  updateActionItemStatus,
  updateSpeakerSummary,
  getAudioRecords,
  renameAudioRecord,
  deleteAudioRecord,
  downloadAudioRecord,
  downloadMeetingFile,
  getAudioDownloadUrl,
  getSummaryRecipients,
  getSummaryRequirements,
  saveSummaryRecipients,
  saveSummaryRequirements,
} from '../api'
import { LANGUAGE_LABELS, ROUTES } from '../constants'
import type {
  AudioRecord,
  InterpretationResultItem,
  InterpretationStatus,
  Meeting,
  MeetingActionItem,
  MeetingFile,
  SpeakerSummaryRecord,
  SystemUserInfo,
  TeamsSummarySendResponse,
} from '../types'
import { buildTranscriptGroups, type TranscriptGroup } from '../utils/transcriptGrouping'
import { buildTranscriptWordHtml } from '../utils/transcriptExport'
import {
  buildUserSummaryPreferenceState,
  clearLegacySharedSummaryStorage,
  MAX_SUMMARY_REQUIREMENTS_LENGTH,
} from '../utils/userSummaryPreferences'
import './InterpretationView.css'
import './HistoryView.css'

const DEFAULT_TITLE = '未命名会议'
const TRANSCRIPT_EXPORT_ALL_KEY = '__all__'

type SpeakerActionStatus = 'idle' | 'loading' | 'done' | 'error'
type SearchMatchKind = 'exact' | 'fuzzy'
type TextSearchMatch = { kind: SearchMatchKind; start: number; end: number }
type TranscriptSearchMatch = { groupIndex: number; kind: SearchMatchKind }
type TranscriptExportSpeakerOption = { key: string; label: string; count: number }

const preferenceSaveLabel = (status: SpeakerActionStatus) => {
  if (status === 'loading') return '保存中...'
  if (status === 'done') return '已保存'
  if (status === 'error') return '保存失败'
  return '设为默认'
}

const sanitizeFilename = (name: string) =>
  name.replace(/[\\/:*?"<>|]/g, '_').trim() || '记录'

const normalizeSearchKeyword = (keyword: string) => keyword.trim().toLowerCase()

const fuzzyDistanceThreshold = (keywordLength: number) => {
  if (keywordLength <= 1) return 0
  if (keywordLength <= 6) return 1
  return Math.min(3, Math.ceil(keywordLength * 0.25))
}

const levenshteinDistanceWithin = (left: string, right: string, maxDistance: number) => {
  if (Math.abs(left.length - right.length) > maxDistance) return maxDistance + 1
  let previous = Array.from({ length: right.length + 1 }, (_, index) => index)

  for (let i = 1; i <= left.length; i++) {
    const current = [i]
    let rowMin = current[0]
    for (let j = 1; j <= right.length; j++) {
      const cost = left[i - 1] === right[j - 1] ? 0 : 1
      const value = Math.min(
        previous[j] + 1,
        current[j - 1] + 1,
        previous[j - 1] + cost,
      )
      current[j] = value
      rowMin = Math.min(rowMin, value)
    }
    if (rowMin > maxDistance) return maxDistance + 1
    previous = current
  }

  return previous[right.length]
}

const hasUsefulOverlap = (left: string, right: string) => {
  const leftChars = new Set(left.replace(/\s+/g, '').split(''))
  const rightChars = right.replace(/\s+/g, '').split('')
  if (leftChars.size === 0 || rightChars.length === 0) return false
  const overlap = rightChars.filter(char => leftChars.has(char)).length
  return overlap >= Math.max(1, Math.ceil(rightChars.length * 0.35))
}

const findTextSearchMatch = (text: string | undefined | null, normalizedKeyword: string): TextSearchMatch | null => {
  if (normalizedKeyword === '') return null
  const value = text || ''
  const lowerValue = value.toLowerCase()
  const exactIndex = lowerValue.indexOf(normalizedKeyword)
  if (exactIndex >= 0) {
    return { kind: 'exact', start: exactIndex, end: exactIndex + normalizedKeyword.length }
  }

  const keywordLength = normalizedKeyword.length
  const maxDistance = fuzzyDistanceThreshold(keywordLength)
  if (maxDistance === 0 || lowerValue.length < Math.max(2, keywordLength - maxDistance)) {
    return null
  }

  let bestMatch: TextSearchMatch | null = null
  let bestDistance = maxDistance + 1
  const minWindowLength = Math.max(2, keywordLength - maxDistance)
  const maxWindowLength = Math.min(lowerValue.length, keywordLength + maxDistance)
  const windowLengths = Array.from(
    { length: maxWindowLength - minWindowLength + 1 },
    (_, index) => minWindowLength + index,
  ).sort((left, right) => {
    const leftDelta = Math.abs(left - keywordLength)
    const rightDelta = Math.abs(right - keywordLength)
    return leftDelta === rightDelta ? right - left : leftDelta - rightDelta
  })

  for (const windowLength of windowLengths) {
    for (let start = 0; start <= lowerValue.length - windowLength; start++) {
      const segment = lowerValue.slice(start, start + windowLength)
      if (!hasUsefulOverlap(segment, normalizedKeyword)) continue
      const distance = levenshteinDistanceWithin(segment, normalizedKeyword, maxDistance)
      if (distance < bestDistance) {
        bestDistance = distance
        bestMatch = { kind: 'fuzzy', start, end: start + windowLength }
        if (distance === 1) return bestMatch
      }
    }
  }

  return bestMatch
}

const groupHasKeyword = (group: TranscriptGroup, normalizedKeyword: string) => {
  if (normalizedKeyword === '') return null
  const matches = [
    findTextSearchMatch(group.sourceText, normalizedKeyword),
    findTextSearchMatch(group.speakerName, normalizedKeyword),
    findTextSearchMatch(group.speakerId, normalizedKeyword),
    ...group.translations.map(translation => findTextSearchMatch(translation.text, normalizedKeyword)),
  ].filter((match): match is TextSearchMatch => match !== null)

  if (matches.some(match => match.kind === 'exact')) return 'exact'
  if (matches.some(match => match.kind === 'fuzzy')) return 'fuzzy'
  return null
}

const renderHighlightedText = (text: string, keyword: string): ReactNode => {
  const normalizedKeyword = normalizeSearchKeyword(keyword)
  if (normalizedKeyword === '') return text

  const fuzzyMatch = findTextSearchMatch(text, normalizedKeyword)
  if (fuzzyMatch?.kind === 'fuzzy') {
    return (
      <>
        {text.slice(0, fuzzyMatch.start)}
        <mark className="history-transcript-highlight history-transcript-highlight--fuzzy">
          {text.slice(fuzzyMatch.start, fuzzyMatch.end)}
        </mark>
        {text.slice(fuzzyMatch.end)}
      </>
    )
  }

  const lowerText = text.toLowerCase()
  const nodes: ReactNode[] = []
  let cursor = 0
  let matchIndex = lowerText.indexOf(normalizedKeyword)

  while (matchIndex >= 0) {
    if (matchIndex > cursor) {
      nodes.push(text.slice(cursor, matchIndex))
    }
    const endIndex = matchIndex + normalizedKeyword.length
    nodes.push(
      <mark className="history-transcript-highlight" key={`${matchIndex}-${endIndex}`}>
        {text.slice(matchIndex, endIndex)}
      </mark>,
    )
    cursor = endIndex
    matchIndex = lowerText.indexOf(normalizedKeyword, cursor)
  }

  if (cursor < text.length) {
    nodes.push(text.slice(cursor))
  }
  return nodes.length > 0 ? nodes : text
}

const targetLanguageLabel = (lang?: string) => {
  if (!lang) return ''
  const lower = lang.toLowerCase()
  if (lower.startsWith('zh')) return LANGUAGE_LABELS['zh-CN'] || lang
  if (lower.startsWith('id')) return LANGUAGE_LABELS['id-ID'] || lang
  if (lower.startsWith('en')) return LANGUAGE_LABELS['en-US'] || lang
  return LANGUAGE_LABELS[lang] || lang
}

const isUnknownTranscriptSpeaker = (value?: string | null) => {
  const normalized = (value || '').trim().toLowerCase()
  return normalized === '' || normalized === 'unknown' || normalized.includes('未识别汇报者')
}

const transcriptSpeakerExportKey = (group: TranscriptGroup) => {
  const speakerId = group.speakerId?.trim()
  if (speakerId && !isUnknownTranscriptSpeaker(speakerId)) {
    return `id:${speakerId}`
  }
  const speakerName = group.speakerName?.trim()
  if (speakerName && !isUnknownTranscriptSpeaker(speakerName)) {
    return `name:${speakerName.toLowerCase()}`
  }
  return ''
}

const resolveTranscriptSpeakerLabel = (
  group: TranscriptGroup,
  speakerMappings: Record<string, string>,
) => {
  const speakerId = group.speakerId?.trim()
  const mappedName = speakerId ? speakerMappings[speakerId]?.trim() : ''
  const speakerName = group.speakerName?.trim()
  if (mappedName && !isUnknownTranscriptSpeaker(mappedName)) return mappedName
  if (speakerName && !isUnknownTranscriptSpeaker(speakerName)) return speakerName
  if (speakerId && !isUnknownTranscriptSpeaker(speakerId)) return speakerId
  return ''
}

const languageBadgeClass = (lang?: string) => {
  const lower = (lang || '').toLowerCase()
  if (lower.startsWith('zh')) return 'si-tri-line-lang-badge si-tri-line-lang-badge--zh'
  if (lower.startsWith('id')) return 'si-tri-line-lang-badge si-tri-line-lang-badge--id'
  if (lower.startsWith('en')) return 'si-tri-line-lang-badge si-tri-line-lang-badge--en'
  return 'si-tri-line-lang-badge'
}

const downloadWord = (title: string, groups: TranscriptGroup[]) => {
  const html = buildTranscriptWordHtml(groups)
  const blob = new Blob(['﻿', html], { type: 'application/msword' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url; a.download = `${sanitizeFilename(title)}.doc`; a.click()
  URL.revokeObjectURL(url)
}

const downloadBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = fileName
  document.body.appendChild(a)
  a.click()
  a.remove()
  window.setTimeout(() => URL.revokeObjectURL(url), 0)
}

// 把会议日期格式化为「2026 年 6 月 03日（周三）」用于发言摘要 PDF。
const formatChineseDate = (src?: string | null): string => {
  const parsed = src ? new Date(src.replace(' ', 'T')) : new Date()
  const d = isNaN(parsed.getTime()) ? new Date() : parsed
  const wk = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${dd}日（${wk}）`
}


export default function HistoryView() {
  // ── Meeting list ────────────────────────────────────────
  const [meetings, setMeetings] = useState<Meeting[]>([])
  const [meetingsLoading, setMeetingsLoading] = useState(false)
  const [selectedMeetingId, setSelectedMeetingId] = useState<number | null>(null)
  const [activeTab, setActiveTab] = useState<'files' | 'transcript' | 'speakers' | 'summary' | 'audio'>('files')

  // ── Session within meeting ───────────────────────────────
  const [sessions, setSessions] = useState<InterpretationStatus[]>([])
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null)

  // ── Transcript tab ──────────────────────────────────────
  const [results, setResults] = useState<InterpretationResultItem[]>([])
  const [resultsLoading, setResultsLoading] = useState(false)
  const [transcriptKeyword, setTranscriptKeyword] = useState('')
  const [transcriptExportSpeakerKey, setTranscriptExportSpeakerKey] = useState(TRANSCRIPT_EXPORT_ALL_KEY)
  const [transcriptExporting, setTranscriptExporting] = useState(false)
  const [activeTranscriptMatchIndex, setActiveTranscriptMatchIndex] = useState(0)
  const transcriptGroupRefs = useRef<Record<number, HTMLDivElement | null>>({})

  // ── Summary tab ─────────────────────────────────────────
  const [summaryText, setSummaryText] = useState<string | null>(null)
  const [summaryLoading, setSummaryLoading] = useState(false)
  const [summaryRequirements, setSummaryRequirements] = useState('')
  const [summaryReqSaveStatus, setSummaryReqSaveStatus] = useState<SpeakerActionStatus>('idle')
  const [teamsPushStatus, setTeamsPushStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle')
  const [summaryPushResult, setSummaryPushResult] = useState<TeamsSummarySendResponse | null>(null)
  const [systemUsers, setSystemUsers] = useState<SystemUserInfo[]>([])
  const [systemUsersLoading, setSystemUsersLoading] = useState(false)
  const [selectedRecipients, setSelectedRecipients] = useState<Set<string>>(new Set())
  const hasFetchedSummaryRef = useRef(false)
  const [recipientSearch, setRecipientSearch] = useState('')
  const [showRecipientDropdown, setShowRecipientDropdown] = useState(false)
  const [recipientSaveStatus, setRecipientSaveStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const recipientPickerRef = useRef<HTMLDivElement>(null)
  const recipientSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)

  // ── Speaker tab ─────────────────────────────────────────
  const [speakerRecords, setSpeakerRecords] = useState<SpeakerSummaryRecord[]>([])
  const [speakerLoading, setSpeakerLoading] = useState(false)
  const [speakerRequirements, setSpeakerRequirements] = useState('')
  const [speakerReqSaveStatus, setSpeakerReqSaveStatus] = useState<SpeakerActionStatus>('idle')
  const [summaryPreferencesLoading, setSummaryPreferencesLoading] = useState(true)
  const [summaryPreferencesLoadError, setSummaryPreferencesLoadError] = useState(false)
  const [speakerRegenStatus, setSpeakerRegenStatus] = useState<Record<string, SpeakerActionStatus>>({})
  const [speakerSaveStatus, setSpeakerSaveStatus] = useState<Record<string, SpeakerActionStatus>>({})
  const [speakerPushStatus, setSpeakerPushStatus] = useState<Record<string, SpeakerActionStatus>>({})
  const [speakerPushResults, setSpeakerPushResults] = useState<Record<string, TeamsSummarySendResponse | null>>({})

  // ── Action items tab ─────────────────────────────────────
  const [actionItems, setActionItems] = useState<MeetingActionItem[]>([])
  const [actionItemsLoading, setActionItemsLoading] = useState(false)
  const [extractingActionItems, setExtractingActionItems] = useState(false)

  // ── Audio tab ─────────────────────────────────────────────
  const [audioRecords, setAudioRecords] = useState<AudioRecord[]>([])
  const [audioLoading, setAudioLoading] = useState(false)
  const [audioKeyword, setAudioKeyword] = useState('')
  const [audioRenameId, setAudioRenameId] = useState<number | null>(null)
  const [audioRenameDraft, setAudioRenameDraft] = useState('')
  const [downloadingAudioId, setDownloadingAudioId] = useState<number | null>(null)
  const [downloadingFileKey, setDownloadingFileKey] = useState('')
  const [downloadError, setDownloadError] = useState('')

  // ── Speaker rename (transcript tab) ──────────────────────────
  const [speakerMappings, setSpeakerMappings] = useState<Record<string, string>>({})
  const [speakerRenameInputs, setSpeakerRenameInputs] = useState<Record<string, string>>({})
  const [speakerRenameSaving, setSpeakerRenameSaving] = useState<Record<string, boolean>>({})

  const selectedMeeting = meetings.find(m => m.id === selectedMeetingId) ?? null
  const transcriptGroups = useMemo(() => buildTranscriptGroups(results), [results])
  const transcriptExportSpeakers = useMemo<TranscriptExportSpeakerOption[]>(() => {
    const options = new Map<string, TranscriptExportSpeakerOption>()
    transcriptGroups.forEach(group => {
      const key = transcriptSpeakerExportKey(group)
      if (!key) return
      const label = resolveTranscriptSpeakerLabel(group, speakerMappings)
      if (!label) return
      const existing = options.get(key)
      if (existing) {
        existing.count += 1
      } else {
        options.set(key, { key, label, count: 1 })
      }
    })
    return Array.from(options.values())
  }, [speakerMappings, transcriptGroups])

  const transcriptSearchTerm = useMemo(() => normalizeSearchKeyword(transcriptKeyword), [transcriptKeyword])
  const transcriptMatches = useMemo<TranscriptSearchMatch[]>(
    () => transcriptSearchTerm === ''
      ? []
      : transcriptGroups.reduce<TranscriptSearchMatch[]>((matches, group, index) => {
          const matchKind = groupHasKeyword(group, transcriptSearchTerm)
          if (matchKind) matches.push({ groupIndex: index, kind: matchKind })
          return matches
        }, []),
    [transcriptGroups, transcriptSearchTerm],
  )
  const transcriptMatchIndexes = useMemo(
    () => transcriptMatches.map(match => match.groupIndex),
    [transcriptMatches],
  )
  const transcriptMatchByGroupIndex = useMemo(() => {
    const map = new Map<number, SearchMatchKind>()
    transcriptMatches.forEach(match => map.set(match.groupIndex, match.kind))
    return map
  }, [transcriptMatches])
  const activeTranscriptMatchKind = transcriptMatches[activeTranscriptMatchIndex]?.kind ?? null

  const chosenRecipients = useMemo(
    () => systemUsers.filter(u => selectedRecipients.has(u.email!)).map(u => u.email!),
    [systemUsers, selectedRecipients]
  )
  const filteredRecipients = useMemo(() => {
    const q = recipientSearch.trim().toLowerCase()
    if (!q) return []
    return systemUsers
      .filter(u => {
        const name = (u.personName || '').toLowerCase()
        const dept = (u.department || '').toLowerCase()
        const email = (u.email || '').toLowerCase()
        return name.includes(q) || dept.includes(q) || email.includes(q)
      })
      .slice(0, 8)
  }, [systemUsers, recipientSearch])

  useEffect(() => {
    if (transcriptMatchIndexes.length === 0) {
      setActiveTranscriptMatchIndex(0)
      return
    }
    setActiveTranscriptMatchIndex(prev => Math.min(prev, transcriptMatchIndexes.length - 1))
  }, [transcriptMatchIndexes.length])

  useEffect(() => {
    if (transcriptExportSpeakerKey === TRANSCRIPT_EXPORT_ALL_KEY) return
    if (!transcriptExportSpeakers.some(option => option.key === transcriptExportSpeakerKey)) {
      setTranscriptExportSpeakerKey(TRANSCRIPT_EXPORT_ALL_KEY)
    }
  }, [transcriptExportSpeakerKey, transcriptExportSpeakers])

  useEffect(() => {
    if (activeTab !== 'transcript' || transcriptMatchIndexes.length === 0) return
    const groupIndex = transcriptMatchIndexes[activeTranscriptMatchIndex]
    if (groupIndex == null) return
    transcriptGroupRefs.current[groupIndex]?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }, [activeTab, activeTranscriptMatchIndex, transcriptMatchIndexes])

  // ── Load meetings ────────────────────────────────────────
  useEffect(() => {
    setMeetingsLoading(true)
    getMeetings()
      .then(res => {
        const list = res.data || []
        setMeetings(list)
        if (list.length > 0 && selectedMeetingId === null) setSelectedMeetingId(list[0].id)
      })
      .catch(() => {})
      .finally(() => setMeetingsLoading(false))
  }, [])

  // ── When meeting changes, reset tabs and load sessions ──
  useEffect(() => {
    if (selectedMeetingId === null) return
    setActiveTab('files')
    setResults([])
    setTranscriptKeyword('')
    setActiveTranscriptMatchIndex(0)
    setSummaryText(null)
    setSpeakerRecords([])
    setSessions([])
    setSelectedSessionId(null)
    setSpeakerRegenStatus({})
    setSpeakerSaveStatus({})
    setSpeakerPushStatus({})
    setSpeakerPushResults({})
    setTeamsPushStatus('idle')
    setSummaryPushResult(null)
    setSpeakerMappings({})
    setSpeakerRenameInputs({})
    setSpeakerRenameSaving({})
    hasFetchedSummaryRef.current = false

    getMeetingSessions(selectedMeetingId)
      .then(res => {
        const list = res.data || []
        setSessions(list)
        if (list.length > 0) setSelectedSessionId(list[0].sessionId)
      })
      .catch(() => {})
  }, [selectedMeetingId])

  useEffect(() => {
    let disposed = false
    try {
      clearLegacySharedSummaryStorage(localStorage)
    } catch {
      // Account settings still come from the backend when browser storage is unavailable.
    }
    setSystemUsersLoading(true)
    setSummaryPreferencesLoading(true)
    setSummaryPreferencesLoadError(false)
    Promise.allSettled([getSystemUsers(), getSummaryRecipients(), getSummaryRequirements()])
      .then(([usersResult, recipientsResult, requirementsResult]) => {
        if (disposed) return

        if (usersResult.status === 'fulfilled') {
          setSystemUsers((usersResult.value.data || []).filter(u => u.email && u.email.trim()))
        } else {
          setSystemUsers([])
        }

        const recipients = recipientsResult.status === 'fulfilled'
          ? recipientsResult.value.data
          : []
        const requirements = requirementsResult.status === 'fulfilled'
          ? requirementsResult.value.data
          : null
        const settings = buildUserSummaryPreferenceState(recipients, requirements)
        setSelectedRecipients(new Set(settings.recipients))
        setSummaryRequirements(settings.meetingSummaryRequirements)
        setSpeakerRequirements(settings.speakerSummaryRequirements)

        if (recipientsResult.status === 'rejected') setRecipientSaveStatus('error')
        setSummaryPreferencesLoadError(requirementsResult.status === 'rejected')
      })
      .finally(() => {
        if (disposed) return
        setSystemUsersLoading(false)
        setSummaryPreferencesLoading(false)
      })

    return () => { disposed = true }
  }, [])

  useEffect(() => {
    const handleClickOutside = (e: MouseEvent) => {
      if (recipientPickerRef.current && !recipientPickerRef.current.contains(e.target as Node)) {
        setShowRecipientDropdown(false)
        setRecipientSearch('')
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  // ── Load tab data lazily ─────────────────────────────────
  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'transcript') return
    setResultsLoading(true)
    getPublicInterpretationResults(selectedSessionId)
      .then(res => setResults(res.data || []))
      .catch(() => setResults([]))
      .finally(() => setResultsLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'transcript') return
    setSpeakerMappings({})
    setSpeakerRenameInputs({})
    setSpeakerRenameSaving({})
    getSessionSpeakerIdentities(selectedSessionId)
      .then(res => {
        const map: Record<string, string> = {}
        const inputs: Record<string, string> = {}
        ;(res.data || []).forEach(item => {
          if (item.speakerId && item.personName) {
            map[item.speakerId] = item.personName
            inputs[item.speakerId] = item.personName
          }
        })
        setSpeakerMappings(map)
        setSpeakerRenameInputs(inputs)
      })
      .catch(() => { /* ignore */ })
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'summary') return

    if (hasFetchedSummaryRef.current) return
    hasFetchedSummaryRef.current = true
    setSummaryLoading(true)
    getMeetingSummary(selectedSessionId)
      .then(res => setSummaryText(res.data?.summary ?? ''))
      .catch(() => setSummaryText(''))
      .finally(() => setSummaryLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) return
    if (activeTab !== 'speakers') return
    setSpeakerLoading(true)
    getSpeakerSummaries(selectedSessionId)
      .then(res => setSpeakerRecords(res.data || []))
      .catch(() => setSpeakerRecords([]))
      .finally(() => setSpeakerLoading(false))
  }, [activeTab, selectedSessionId])

  useEffect(() => {
    if (!selectedSessionId) { setActionItems([]); return }
    setActionItemsLoading(true)
    getActionItems(selectedSessionId)
      .then(res => setActionItems(res.data || []))
      .catch(() => setActionItems([]))
      .finally(() => setActionItemsLoading(false))
  }, [selectedSessionId])

  const handleExtractActionItems = async () => {
    if (!selectedSessionId) return
    setExtractingActionItems(true)
    try {
      const res = await extractActionItems(selectedSessionId, selectedMeetingId)
      setActionItems(res.data || [])
    } catch { /* ignore */ }
    finally { setExtractingActionItems(false) }
  }

  // ── Audio tab handlers ───────────────────────────────────
  useEffect(() => {
    if (activeTab !== 'audio') return
    setAudioLoading(true)
    getAudioRecords(audioKeyword, 1, 50, selectedMeetingId ?? undefined)
      .then(res => setAudioRecords(res.data?.items ?? []))
      .catch(() => setAudioRecords([]))
      .finally(() => setAudioLoading(false))
  }, [activeTab, audioKeyword, selectedMeetingId])

  const handleAudioDelete = async (id: number) => {
    if (!window.confirm('删除该录音文件？')) return
    try {
      await deleteAudioRecord(id)
      setAudioRecords(prev => prev.filter(r => r.id !== id))
    } catch { /* ignore */ }
  }

  const handleAudioRenameCommit = async (id: number) => {
    const name = audioRenameDraft.trim()
    if (!name) { setAudioRenameId(null); return }
    try {
      await renameAudioRecord(id, name)
      setAudioRecords(prev => prev.map(r => r.id === id ? { ...r, name } : r))
    } catch { /* ignore */ }
    finally { setAudioRenameId(null) }
  }

  const handleAudioDownload = async (record: AudioRecord) => {
    setDownloadingAudioId(record.id)
    setDownloadError('')
    try {
      const blob = await downloadAudioRecord(record.id)
      downloadBlob(blob, `${sanitizeFilename(record.name)}.wav`)
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : '下载录音失败')
    } finally {
      setDownloadingAudioId(null)
    }
  }

  const handleSpeakerRename = async (speakerId: string) => {
    if (!selectedSessionId) return
    const personName = (speakerRenameInputs[speakerId] || '').trim()
    setSpeakerRenameSaving(prev => ({ ...prev, [speakerId]: true }))
    try {
      await mapSessionSpeakerIdentity(selectedSessionId, speakerId, personName)
      setSpeakerMappings(prev => ({ ...prev, [speakerId]: personName }))
      // Reload results so transcript display reflects the updated name
      const res = await getPublicInterpretationResults(selectedSessionId)
      setResults(res.data || [])
    } catch { /* ignore */ }
    finally { setSpeakerRenameSaving(prev => ({ ...prev, [speakerId]: false })) }
  }

  const handleTranscriptExport = async () => {
    if (!selectedSessionId) return
    setTranscriptExporting(true)
    setDownloadError('')
    try {
      const res = await getPublicInterpretationResults(selectedSessionId)
      const latestResults = res.data || []
      const latestGroups = buildTranscriptGroups(latestResults)
      setResults(latestResults)

      const groupsToExport = transcriptExportSpeakerKey === TRANSCRIPT_EXPORT_ALL_KEY
        ? latestGroups
        : latestGroups.filter(group => transcriptSpeakerExportKey(group) === transcriptExportSpeakerKey)

      if (groupsToExport.length === 0) {
        setDownloadError('当前导出范围暂无文本')
        return
      }

      const selectedSpeaker = transcriptExportSpeakers.find(option => option.key === transcriptExportSpeakerKey)
      const exportScope = transcriptExportSpeakerKey === TRANSCRIPT_EXPORT_ALL_KEY
        ? '全部文本'
        : (selectedSpeaker?.label || resolveTranscriptSpeakerLabel(groupsToExport[0], speakerMappings) || '汇报者')
      downloadWord(`${selectedMeeting?.title || '同传记录'}-${exportScope}`, groupsToExport)
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : '导出文本失败')
    } finally {
      setTranscriptExporting(false)
    }
  }

  const formatDuration = (ms: number) => {
    const s = Math.round(ms / 1000)
    const m = Math.floor(s / 60)
    return m > 0 ? `${m}分${s % 60}秒` : `${s}秒`
  }

  const formatBytes = (bytes: number) => {
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(0)} KB`
    return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  }

  const handleToggleActionItem = async (item: MeetingActionItem) => {
    const next = item.status === 'done' ? 'pending' : 'done'
    try {
      const res = await updateActionItemStatus(item.id, next)
      setActionItems(prev => prev.map(a => a.id === item.id ? res.data : a))
    } catch { /* ignore */ }
  }

  const jumpTranscriptMatch = (step: number) => {
    if (transcriptMatchIndexes.length === 0) return
    setActiveTranscriptMatchIndex(prev => (
      (prev + step + transcriptMatchIndexes.length) % transcriptMatchIndexes.length
    ))
  }

  const handleTranscriptSearchKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key !== 'Enter') return
    event.preventDefault()
    jumpTranscriptMatch(1)
  }

  const refetchSummary = async () => {
    if (!selectedSessionId) return
    setSummaryLoading(true)
    hasFetchedSummaryRef.current = true
    try {
      const res = await regenerateMeetingSummary(selectedSessionId, summaryRequirements.trim() || undefined)
      const text = res.data?.summary ?? ''
      setSummaryText(text)
      if (text) {
        const recipients = Array.from(selectedRecipients)
        if (recipients.length > 0) {
          void pushSummaryPdf(selectedMeeting?.title || '会议总结', text, recipients)
        }
      }
    } catch { setSummaryText('') }
    finally { setSummaryLoading(false) }
  }

  // 会议总结：把摘要内容发送给所选 Teams 账号。
  const pushSummaryPdf = async (title: string, text: string, recipients: string[]) => {
    if (recipients.length === 0) {
      setSummaryPushResult({
        sent: false,
        sentCount: 0,
        failedCount: 0,
        recipients: [],
        failures: [{ recipient: '未选择账号', error: '请选择要通知的账号' }],
      })
      setTeamsPushStatus('error')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
      return
    }
    setTeamsPushStatus('loading')
    setSummaryPushResult(null)
    try {
      const content = `${title || '会议总结'}\n\n${text}`
      const res = await sendTeamsSummaryToUsers(content, recipients)
      setSummaryPushResult(res)
      setTeamsPushStatus(res.sent ? 'done' : 'error')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    } catch (error) {
      setSummaryPushResult(buildSendFailureResult(recipients, error))
      setTeamsPushStatus('error')
      setTimeout(() => setTeamsPushStatus('idle'), 3000)
    }
  }

  const saveSummaryRequirementsDefault = async () => {
    setSummaryReqSaveStatus('loading')
    try {
      await saveSummaryRequirements({ meetingSummaryRequirements: summaryRequirements })
      setSummaryPreferencesLoadError(false)
      setSummaryReqSaveStatus('done')
      setTimeout(() => setSummaryReqSaveStatus('idle'), 1500)
    } catch {
      setSummaryReqSaveStatus('error')
      setTimeout(() => setSummaryReqSaveStatus('idle'), 3000)
    }
  }

  const saveSpeakerRequirementsDefault = async () => {
    setSpeakerReqSaveStatus('loading')
    try {
      await saveSummaryRequirements({ speakerSummaryRequirements: speakerRequirements })
      setSummaryPreferencesLoadError(false)
      setSpeakerReqSaveStatus('done')
      setTimeout(() => setSpeakerReqSaveStatus('idle'), 1500)
    } catch {
      setSpeakerReqSaveStatus('error')
      setTimeout(() => setSpeakerReqSaveStatus('idle'), 3000)
    }
  }

  const persistRecipients = (list: string[]) => {
    if (recipientSaveTimerRef.current) clearTimeout(recipientSaveTimerRef.current)
    setRecipientSaveStatus('saving')
    recipientSaveTimerRef.current = setTimeout(() => {
      saveSummaryRecipients(list)
        .then(() => { setRecipientSaveStatus('saved'); setTimeout(() => setRecipientSaveStatus('idle'), 1500) })
        .catch(() => { setRecipientSaveStatus('error'); setTimeout(() => setRecipientSaveStatus('idle'), 3000) })
    }, 300)
  }

  const toggleRecipient = (email: string) => {
    setSelectedRecipients(prev => {
      const next = new Set(prev)
      next.has(email) ? next.delete(email) : next.add(email)
      persistRecipients(Array.from(next))
      return next
    })
  }

  const renderRecipientPicker = (label: string) => {
    if (systemUsersLoading) return <div className="history-summary-send-hint">正在加载用户列表...</div>
    if (systemUsers.length === 0) return <div className="history-summary-send-hint">暂无可发送的用户，请先在系统管理中导入用户信息。</div>
    const saveHint = recipientSaveStatus === 'saving' ? '保存中...'
      : recipientSaveStatus === 'saved' ? '已保存'
      : recipientSaveStatus === 'error' ? '保存失败，请重试'
      : null
    return (
      <div className="history-recipient-section" ref={recipientPickerRef}>
        <div className="history-summary-send-label">
          {label}
          {saveHint && (
            <span className={`history-recipient-save-hint history-recipient-save-hint--${recipientSaveStatus}`}>
              {saveHint}
            </span>
          )}
        </div>
        <div className="history-recipient-box">
          {Array.from(selectedRecipients).map(email => {
            const user = systemUsers.find(u => u.email === email)
            const displayName = user
              ? `${user.personName}${user.department ? ` · ${user.department}` : ''}`
              : email
            return (
              <span key={email} className="history-recipient-tag">
                {displayName}
                <button
                  className="history-recipient-tag-x"
                  type="button"
                  onMouseDown={e => { e.preventDefault(); toggleRecipient(email) }}
                >×</button>
              </span>
            )
          })}
          <input
            className="history-recipient-input"
            placeholder={selectedRecipients.size === 0 ? '搜索姓名或部门添加...' : '继续添加...'}
            value={recipientSearch}
            onChange={e => { setRecipientSearch(e.target.value); setShowRecipientDropdown(true) }}
            onFocus={() => setShowRecipientDropdown(true)}
          />
          {showRecipientDropdown && filteredRecipients.length > 0 && (
            <div className="history-recipient-dropdown">
              {filteredRecipients.map(u => (
                <div
                  key={u.email}
                  className="history-recipient-dropdown-item"
                  onMouseDown={e => {
                    e.preventDefault()
                    toggleRecipient(u.email!)
                    setRecipientSearch('')
                    setShowRecipientDropdown(false)
                  }}
                >
                  {u.personName}{u.department ? ` · ${u.department}` : ''}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    )
  }

  const buildSendFailureResult = (recipients: string[], error: unknown): TeamsSummarySendResponse => {
    const message = error instanceof Error ? error.message : '发送失败'
    return {
      sent: false,
      sentCount: 0,
      failedCount: recipients.length,
      recipients: [],
      failures: recipients.map(recipient => ({ recipient, error: message })),
      error: message,
    }
  }

  const recipientDisplayName = (recipient: string) => {
    const user = systemUsers.find(item => item.email === recipient)
    return user?.personName || user?.email || recipient
  }

  const renderTeamsSendResult = (result: TeamsSummarySendResponse | null) => {
    if (!result) return null
    const successes = result.recipients ?? []
    const failures = result.failures ?? []
    return (
      <div className="history-teams-send-result">
        {successes.length > 0 && (
          <div className="history-teams-send-group history-teams-send-group--success">
            <div className="history-teams-send-title">通知成功账号（{successes.length}）</div>
            <div className="history-teams-send-tags">
              {successes.map(target => {
                const label = target.displayName || target.email || target.recipient
                const detail = target.email && target.email !== label ? target.email : target.recipient
                return (
                  <span key={`${target.aadId}-${target.recipient}`} className="history-teams-send-tag">
                    <strong>{label}</strong>
                    {detail && <small>{detail}</small>}
                  </span>
                )
              })}
            </div>
          </div>
        )}
        {failures.length > 0 && (
          <div className="history-teams-send-group history-teams-send-group--failure">
            <div className="history-teams-send-title">通知失败账号（{failures.length}）</div>
            <div className="history-teams-send-tags">
              {failures.map(item => (
                <span key={`${item.recipient}-${item.error}`} className="history-teams-send-tag">
                  <strong>{recipientDisplayName(item.recipient)}</strong>
                  <small>{item.error}</small>
                </span>
              ))}
            </div>
          </div>
        )}
        {successes.length === 0 && failures.length === 0 && result.error && (
          <div className="history-teams-send-empty">{result.error}</div>
        )}
      </div>
    )
  }

  const regenerateSpeakerRecord = async (record: SpeakerSummaryRecord, statusKey: string, idx: number) => {
    if (!record.id) return
    setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'loading' }))
    try {
      const res = await regenerateSpeakerSummary(record.id, speakerRequirements.trim() || undefined)
      const updated = { ...record, title: res.data?.title ?? null, summary: res.data?.summary ?? '' }
      setSpeakerRecords(prev => prev.map(item =>
        item.id === record.id ? { ...item, title: updated.title, summary: updated.summary } : item
      ))
      setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'done' }))
      const recipients = Array.from(selectedRecipients)
      if (recipients.length > 0 && updated.summary) {
        void pushSpeakerSummaryToTeams(updated, recipients, statusKey, idx + 1)
      }
      setTimeout(() => {
        setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 1500)
    } catch {
      setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'error' }))
      setTimeout(() => {
        setSpeakerRegenStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    }
  }

  const saveSpeakerRecord = async (record: SpeakerSummaryRecord, statusKey: string) => {
    if (!record.id || !record.speakerName.trim() || !record.summary.trim()) return
    setSpeakerSaveStatus(prev => ({ ...prev, [statusKey]: 'loading' }))
    try {
      const res = await updateSpeakerSummary(record.id, {
        speakerName: record.speakerName.trim(),
        summary: record.summary,
      })
      setSpeakerRecords(prev => prev.map(item => item.id === record.id ? res.data : item))
      setSpeakerSaveStatus(prev => ({ ...prev, [statusKey]: 'done' }))
      setTimeout(() => {
        setSpeakerSaveStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 1500)
    } catch {
      setSpeakerSaveStatus(prev => ({ ...prev, [statusKey]: 'error' }))
      setTimeout(() => {
        setSpeakerSaveStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    }
  }

  // 发言摘要：以 Teams 文本消息发送给所选账号，并展示每个账号的成功/失败结果。
  const pushSpeakerSummaryToTeams = async (
    record: SpeakerSummaryRecord,
    recipients: string[],
    statusKey: string,
    sequence: number,
  ) => {
    if (recipients.length === 0) return
    setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'loading' }))
    setSpeakerPushResults(prev => ({ ...prev, [statusKey]: null }))
    try {
      const meetingName = selectedMeeting?.title || '会议'
      const speakerName = record.speakerName || '发言人'
      const dateText = formatChineseDate(
        selectedMeeting?.scheduledTime || selectedMeeting?.createTime || record.createTime)
      const content = `${meetingName} · ${speakerName}发言摘要（第${sequence}位发言 · ${dateText}）\n\n${record.summary || ''}`
      const res = await sendTeamsSummaryToUsers(content, recipients)
      setSpeakerPushResults(prev => ({ ...prev, [statusKey]: res }))
      setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: res.sent ? 'done' : 'error' }))
      setTimeout(() => {
        setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    } catch (error) {
      setSpeakerPushResults(prev => ({ ...prev, [statusKey]: buildSendFailureResult(recipients, error) }))
      setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'error' }))
      setTimeout(() => {
        setSpeakerPushStatus(prev => ({ ...prev, [statusKey]: 'idle' }))
      }, 3000)
    }
  }

  const deleteSessionAndRefresh = async (sessionId: string) => {
    if (!window.confirm('删除该同传会话记录？')) return
    await deleteInterpretationSession(sessionId)
    setSessions(prev => prev.filter(s => s.sessionId !== sessionId))
    if (selectedSessionId === sessionId) {
      const remaining = sessions.filter(s => s.sessionId !== sessionId)
      setSelectedSessionId(remaining[0]?.sessionId ?? null)
    }
  }

  const deleteMeetingAndRefresh = async (meetingId: number) => {
    if (!window.confirm('删除该会议及其所有文件记录？')) return
    await deleteMeeting(meetingId)
    setMeetings(prev => prev.filter(m => m.id !== meetingId))
    if (selectedMeetingId === meetingId) {
      const remaining = meetings.filter(m => m.id !== meetingId)
      setSelectedMeetingId(remaining[0]?.id ?? null)
    }
  }

  const downloadOriginalFile = async (meetingId: number, fileId: number, fileName: string) => {
    const fileKey = `${meetingId}:${fileId}`
    setDownloadingFileKey(fileKey)
    setDownloadError('')
    try {
      const blob = await downloadMeetingFile(meetingId, fileId)
      downloadBlob(blob, fileName)
    } catch (err) {
      setDownloadError(err instanceof Error ? err.message : '下载原文件失败')
    } finally {
      setDownloadingFileKey('')
    }
  }

  // ── Renders ──────────────────────────────────────────────
  const renderFiles = (files: MeetingFile[], meetingId: number) => {
    if (files.length === 0) return (
      <div className="si-tri-empty">暂无上传文件</div>
    )
    return files.map(f => {
      const fileKey = `${meetingId}:${f.id}`
      const isDownloading = downloadingFileKey === fileKey
      return (
        <div key={f.id} className="history-meeting-file-card">
          <div className="history-meeting-file-header">
            <span className="history-meeting-file-name">{f.fileName}</span>
            {f.fileType && <span className="history-meeting-file-type">{f.fileType.toUpperCase()}</span>}
            {f.createTime && <span className="history-meeting-file-time">{f.createTime}</span>}
            <button
              className="history-file-toggle-btn"
              onClick={() => void downloadOriginalFile(meetingId, f.id, f.fileName)}
              title="下载原始文件"
              disabled={isDownloading}
            >{isDownloading ? '下载中...' : '下载原文件'}</button>
          </div>
        </div>
      )
    })
  }

  return (
    <div className="si-root">
      <header className="si-topbar">
        <div className="si-topbar-left">
          <h1 className="si-brand">历史记录</h1>
        </div>
        <div className="si-topbar-right">
          <button className="si-pill-btn" onClick={() => { window.location.hash = ROUTES.HOME }}>
            返回同传
          </button>
        </div>
      </header>

      <main className="history-main">
        {/* ── 左侧：会议列表 ── */}
        <aside className="history-sidebar">
          <div className="history-list">
            {meetingsLoading && <div className="history-empty">加载中...</div>}
            {!meetingsLoading && meetings.length === 0 && (
              <div className="history-empty">暂无会议，请先在会议页新建会议并上传会议文件</div>
            )}
            {meetings.map(m => (
              <div
                key={m.id}
                className={`history-item ${selectedMeetingId === m.id ? 'history-item--active' : ''}`}
              >
                <button
                  className="history-item-main"
                  onClick={() => setSelectedMeetingId(m.id)}
                  type="button"
                >
                  <span className="history-item-title">{m.title || DEFAULT_TITLE}</span>
                  {m.scheduledTime && (
                    <span className="history-item-meta">{m.scheduledTime}</span>
                  )}
                  <span className="history-item-meta">
                    {(m.files?.length ?? 0)} 个文件
                    {sessions.length > 0 && selectedMeetingId === m.id
                      ? ` · ${sessions.length} 场同传`
                      : ''}
                  </span>
                </button>
              </div>
            ))}
          </div>
        </aside>

        {/* ── 右侧：会议详情 ── */}
        <section className="history-detail">
          {!selectedMeeting ? (
            <div className="si-tri-empty" style={{ marginTop: '4rem' }}>请从左侧选择一场会议</div>
          ) : (
            <>
              <div className="history-detail-toolbar">
                <div>
                  <h2>{selectedMeeting.title || DEFAULT_TITLE}</h2>
                  {selectedMeeting.scheduledTime && <p>{selectedMeeting.scheduledTime}</p>}
                </div>
                <div className="history-detail-actions">
                  <button
                    className="history-danger"
                    onClick={() => { void deleteMeetingAndRefresh(selectedMeeting.id) }}
                  >删除会议</button>
                </div>
              </div>

              {/* 会话选择（若有多场同传） */}
              {sessions.length > 1 && (
                <div className="history-session-selector">
                  <label className="history-session-label">同传会话</label>
                  <select
                    value={selectedSessionId ?? ''}
                    onChange={e => {
                      setSelectedSessionId(e.target.value || null)
                      hasFetchedSummaryRef.current = false
                      setSummaryText(null)
                      setResults([])
                      setSpeakerRecords([])
                      setSpeakerRegenStatus({})
                      setSpeakerPushStatus({})
                      setSpeakerPushResults({})
                      setSummaryPushResult(null)
                    }}
                  >
                    {sessions.map(s => (
                      <option key={s.sessionId} value={s.sessionId}>
                        {s.startTime ? new Date(s.startTime).toLocaleString() : s.sessionId}
                        {' '}· {s.resultCount ?? 0} 条文本
                      </option>
                    ))}
                  </select>
                  {selectedSessionId && (
                    <button
                      className="history-danger"
                      style={{ fontSize: '0.8rem', padding: '3px 8px' }}
                      onClick={() => { void deleteSessionAndRefresh(selectedSessionId) }}
                    >删除</button>
                  )}
                </div>
              )}
              {sessions.length === 1 && selectedSessionId && (
                <div className="history-session-selector">
                  <span className="history-session-label">同传会话</span>
                  <span style={{ fontSize: '0.85rem', color: '#475569' }}>
                    {sessions[0].startTime ? new Date(sessions[0].startTime).toLocaleString() : sessions[0].sessionId}
                    {' '}· {sessions[0].resultCount ?? 0} 条文本
                  </span>
                  <button
                    className="history-danger"
                    style={{ fontSize: '0.8rem', padding: '3px 8px' }}
                    onClick={() => { void deleteSessionAndRefresh(selectedSessionId) }}
                  >删除</button>
                </div>
              )}

              {downloadError && (
                <div className="history-inline-error">
                  <span>{downloadError}</span>
                  <button type="button" onClick={() => setDownloadError('')}>关闭</button>
                </div>
              )}

              {/* Tab 导航 */}
              <div className="history-tab-nav">
                <button className={`history-tab-btn${activeTab === 'files' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('files')}>文件总结</button>
                <button className={`history-tab-btn${activeTab === 'transcript' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('transcript')}>文本记录</button>
                <button className={`history-tab-btn${activeTab === 'speakers' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('speakers')}>发言摘要</button>
                <button className={`history-tab-btn${activeTab === 'summary' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('summary')}>会议总结</button>
                <button className={`history-tab-btn${activeTab === 'audio' ? ' history-tab-btn--active' : ''}`} onClick={() => setActiveTab('audio')}>整场录音</button>
              </div>

              {/* ── 文件总结 Tab ── */}
              {activeTab === 'files' && (
                <div className="history-tab-content">
                  {renderFiles(selectedMeeting.files ?? [], selectedMeeting.id)}
                </div>
              )}

              {/* ── 文本记录 Tab ── */}
              {activeTab === 'transcript' && (
                <div className="history-tab-content">
                  {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无文本记录</div>}
                  {selectedSessionId && resultsLoading && <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>}
                  {selectedSessionId && !resultsLoading && (
                    <>
                      <div className="history-transcript-toolbar">
                        <label className="history-transcript-search">
                          <span>寻找关键词</span>
                          <input
                            value={transcriptKeyword}
                            onChange={e => setTranscriptKeyword(e.target.value)}
                            onKeyDown={handleTranscriptSearchKeyDown}
                            placeholder="输入任务、人名或文本"
                          />
                        </label>
                        <div className="history-transcript-search-actions">
                          <span className={transcriptKeyword.trim() && transcriptMatchIndexes.length === 0 ? 'history-transcript-search-count history-transcript-search-count--empty' : 'history-transcript-search-count'}>
                            {transcriptKeyword.trim()
                              ? transcriptMatchIndexes.length > 0
                                ? `${activeTranscriptMatchIndex + 1} / ${transcriptMatchIndexes.length}${activeTranscriptMatchKind === 'fuzzy' ? ' · 模糊' : ''}`
                                : '无匹配'
                              : '未搜索'}
                          </span>
                          <button
                            type="button"
                            className="history-transcript-nav-btn"
                            disabled={transcriptMatchIndexes.length === 0}
                            onClick={() => jumpTranscriptMatch(-1)}
                          >上一个</button>
                          <button
                            type="button"
                            className="history-transcript-nav-btn"
                            disabled={transcriptMatchIndexes.length === 0}
                            onClick={() => jumpTranscriptMatch(1)}
                          >下一个</button>
                          <button
                            type="button"
                            className="history-transcript-nav-btn"
                            disabled={!transcriptKeyword.trim()}
                            onClick={() => { setTranscriptKeyword(''); setActiveTranscriptMatchIndex(0) }}
                          >清空</button>
                        </div>
                        <div className="history-transcript-export">
                          <label className="history-transcript-export-select">
                            <span>导出范围</span>
                            <select
                              value={transcriptExportSpeakerKey}
                              onChange={e => setTranscriptExportSpeakerKey(e.target.value)}
                              disabled={transcriptExporting || results.length === 0}
                            >
                              <option value={TRANSCRIPT_EXPORT_ALL_KEY}>全部文本</option>
                              {transcriptExportSpeakers.map(option => (
                                <option key={option.key} value={option.key}>
                                  {option.label}（{option.count}段）
                                </option>
                              ))}
                            </select>
                          </label>
                          <button
                            className="history-summary-export-btn"
                            onClick={() => { void handleTranscriptExport() }}
                            disabled={results.length === 0 || transcriptExporting}
                          >{transcriptExporting ? '导出中...' : '导出 Word'}</button>
                        </div>
                      </div>
                      {/* ── 说话人重命名 ── */}
                      {(() => {
                        const uniqueSpeakerIds = Array.from(new Set(
                          transcriptGroups.map(g => g.speakerId).filter((id): id is string => !!id)
                        ))
                        if (uniqueSpeakerIds.length === 0) return null
                        return (
                          <div className="history-speaker-rename-section">
                            <div className="history-speaker-rename-title">说话人</div>
                            {uniqueSpeakerIds.map(speakerId => (
                              <div key={speakerId} className="history-speaker-rename-row">
                                <span className="history-speaker-rename-id">{speakerId}</span>
                                <span className="history-speaker-rename-arrow">→</span>
                                <input
                                  className="history-speaker-rename-input"
                                  value={speakerRenameInputs[speakerId] ?? ''}
                                  onChange={e => setSpeakerRenameInputs(prev => ({ ...prev, [speakerId]: e.target.value }))}
                                  placeholder={speakerId}
                                />
                                <button
                                  className="history-speaker-rename-btn"
                                  disabled={speakerRenameSaving[speakerId]}
                                  onClick={() => { void handleSpeakerRename(speakerId) }}
                                >
                                  {speakerRenameSaving[speakerId] ? '保存中...' : '保存'}
                                </button>
                              </div>
                            ))}
                          </div>
                        )
                      })()}
                      <div className="si-tri-transcript-dock history-transcripts">
                        <div className="si-tri-transcript-dock-inner">
                          {results.length === 0 && <div className="si-tri-empty">暂无文本记录</div>}
                          {transcriptGroups.map((g, groupIndex) => {
                              const speaker = speakerMappings[g.speakerId || ''] || g.speakerName || g.speakerId || null
                              const matchKind = transcriptMatchByGroupIndex.get(groupIndex) ?? null
                              const isMatched = matchKind !== null
                              const isActiveMatch = transcriptMatchIndexes[activeTranscriptMatchIndex] === groupIndex
                              return (
                                <div
                                  key={g.id}
                                  ref={node => { transcriptGroupRefs.current[groupIndex] = node }}
                                  className={`si-tri-block${isMatched ? ' history-transcript-block--matched' : ''}${matchKind === 'fuzzy' ? ' history-transcript-block--fuzzy' : ''}${isActiveMatch ? ' history-transcript-block--active' : ''}`}
                                >
                                  <div className="si-tri-share-line">
                                    {speaker
                                      ? <span className="si-tri-line-lang-badge">{renderHighlightedText(speaker, transcriptKeyword)}</span>
                                      : <span className="si-tri-line-lang-badge si-tri-line-lang-badge--unknown">?</span>}
                                    {renderHighlightedText(g.sourceText, transcriptKeyword)}
                                  </div>
                                  {g.translations.map((translation, ti) => (
                                    <div key={`${translation.targetLang || 'unknown'}-${ti}`} className="si-tri-share-line si-tri-share-line--translated">
                                      {translation.targetLang && (
                                        <span className={languageBadgeClass(translation.targetLang)}>
                                          {targetLanguageLabel(translation.targetLang)}
                                        </span>
                                      )}
                                      {renderHighlightedText(translation.text, transcriptKeyword)}
                                    </div>
                                  ))}
                                </div>
                              )
                            })}
                        </div>
                      </div>
                    </>
                  )}
                </div>
              )}

              {/* ── 发言摘要 Tab ── */}
              {activeTab === 'speakers' && (() => {
                return (
                  <div className="history-tab-content">
                    <div className="history-summary-requirements">
                      <div className="history-summary-requirements-header">
                        <label className="history-summary-requirements-label">发言摘要提示词（可选）</label>
                        <button
                          className="history-summary-requirements-default-btn"
                          onClick={() => { void saveSpeakerRequirementsDefault() }}
                          disabled={summaryPreferencesLoading || speakerReqSaveStatus === 'loading'}
                          title="保存为默认提示词"
                        >
                          {preferenceSaveLabel(speakerReqSaveStatus)}
                        </button>
                      </div>
                      <textarea
                        className="history-summary-requirements-input"
                        value={speakerRequirements}
                        onChange={e => setSpeakerRequirements(e.target.value)}
                        disabled={summaryPreferencesLoading}
                        maxLength={MAX_SUMMARY_REQUIREMENTS_LENGTH}
                        placeholder="例如：突出决策、风险和行动项；每条要点不超过 30 字；保留产品名和数字。"
                        rows={3}
                      />
                      {summaryPreferencesLoadError && (
                        <div className="history-summary-edit-hint">账号默认提示词加载失败，请重新保存后再使用。</div>
                      )}
                    </div>
                    {renderRecipientPicker('发言摘要发送账号（默认，修改后自动保存）')}
                    {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无发言摘要</div>}
                    {selectedSessionId && speakerLoading && <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>}
                    {selectedSessionId && !speakerLoading && speakerRecords.length === 0 && (
                      <div className="history-summary-empty">暂无发言人摘要</div>
                    )}
                    {selectedSessionId && !speakerLoading && speakerRecords.map((rec, idx) => {
                      const statusKey = String(rec.id ?? `${rec.sessionId || 'speaker'}-${idx}`)
                      const regenStatus = speakerRegenStatus[statusKey] || 'idle'
                      const saveStatus = speakerSaveStatus[statusKey] || 'idle'
                      const pushStatus = speakerPushStatus[statusKey] || 'idle'
                      return (
                        <div key={statusKey} className="history-speaker-record">
                          <div className="history-speaker-record-header">
                            <div className="history-speaker-record-title-group">
                              <input
                                className="history-speaker-record-name-input"
                                value={rec.speakerName}
                                onChange={e => setSpeakerRecords(prev => prev.map((r, i) =>
                                  i === idx ? { ...r, speakerName: e.target.value } : r
                                ))}
                                aria-label={`第 ${idx + 1} 位汇报人姓名`}
                                placeholder="汇报人姓名"
                                spellCheck={false}
                              />
                              {rec.title && <span className="history-speaker-record-title">· {rec.title}</span>}
                              {rec.createTime && (
                                <span className="history-speaker-record-time">{new Date(rec.createTime).toLocaleTimeString()}</span>
                              )}
                            </div>
                            <div className="history-speaker-record-actions">
                              <button
                                className="history-summary-regen-btn history-speaker-save-btn"
                                onClick={() => { void saveSpeakerRecord(rec, statusKey) }}
                                disabled={!rec.id || !rec.speakerName.trim() || !rec.summary.trim() || saveStatus === 'loading'}
                              >
                                {saveStatus === 'done' ? '已保存' : saveStatus === 'error' ? '保存失败' : saveStatus === 'loading' ? '保存中...' : '保存'}
                              </button>
                              <button
                                className="history-summary-regen-btn"
                                onClick={() => { void regenerateSpeakerRecord(rec, statusKey, idx) }}
                                disabled={!rec.id || regenStatus === 'loading'}
                              >
                                {regenStatus === 'done' ? '已重新生成' : regenStatus === 'error' ? '生成失败' : regenStatus === 'loading' ? '生成中...' : '按提示词重新生成'}
                              </button>
                              <button
                                className="history-summary-export-btn"
                                onClick={() => { void pushSpeakerSummaryToTeams(rec, chosenRecipients, statusKey, idx + 1) }}
                                disabled={pushStatus === 'loading' || chosenRecipients.length === 0}
                              >
                                {pushStatus === 'done' ? '已发送 ✓' : pushStatus === 'error' ? '发送失败' : pushStatus === 'loading' ? '发送中...' : `发送到 Teams（${chosenRecipients.length} 人）`}
                              </button>
                            </div>
                          </div>
                          <textarea
                            className="history-speaker-record-edit"
                            value={rec.summary}
                            onChange={e => setSpeakerRecords(prev => prev.map((r, i) => i === idx ? { ...r, summary: e.target.value } : r))}
                            spellCheck={false}
                          />
                          <div className="history-summary-edit-hint">修改汇报人姓名和摘要正文后，点「保存」一次性保存全部修改；也可直接发送当前内容到 Teams</div>
                          {renderTeamsSendResult(speakerPushResults[statusKey] ?? null)}
                        </div>
                      )
                    })}
                  </div>
                )
              })()}

              {/* ── 会议总结 Tab ── */}
              {activeTab === 'summary' && (() => {
                const displayed = summaryText
                return (
                  <div className="history-summary-tab">
                    <div className="history-summary-requirements">
                      <div className="history-summary-requirements-header">
                        <label className="history-summary-requirements-label">会议总结提示词（可选）</label>
                        <button className="history-summary-requirements-default-btn"
                          onClick={() => { void saveSummaryRequirementsDefault() }}
                          disabled={summaryPreferencesLoading || summaryReqSaveStatus === 'loading'}
                          title="保存为默认提示词">{preferenceSaveLabel(summaryReqSaveStatus)}</button>
                      </div>
                      <textarea
                        className="history-summary-requirements-input"
                        value={summaryRequirements}
                        onChange={e => setSummaryRequirements(e.target.value)}
                        disabled={summaryPreferencesLoading}
                        maxLength={MAX_SUMMARY_REQUIREMENTS_LENGTH}
                        placeholder="例如：重点突出决议和待办事项，输出中英双语，按议题分段..."
                        rows={3}
                      />
                      {summaryPreferencesLoadError && (
                        <div className="history-summary-edit-hint">账号默认提示词加载失败，请重新保存后再使用。</div>
                      )}
                    </div>
                    <div className="history-summary-send-settings">
                      {renderRecipientPicker('会议总结发送账号（默认，修改后自动保存）')}
                    </div>
                    {!selectedSessionId && <div className="si-tri-empty">该会议尚未进行同传，暂无会议总结</div>}
                    {selectedSessionId && (summaryLoading || displayed === null) && (
                      <div className="history-summary-loading"><span className="history-summary-spinner" />会议总结生成中...</div>
                    )}
                    {selectedSessionId && !summaryLoading && displayed !== null && displayed === '' && (
                      <div className="history-summary-empty">该会话没有可用的文字记录，无法生成总结</div>
                    )}
                    {selectedSessionId && !summaryLoading && (displayed === null || displayed === '') && (
                      <div className="history-summary-regen-row">
                        <button className="history-summary-regen-btn" onClick={refetchSummary}>生成总结</button>
                      </div>
                    )}
                    {selectedSessionId && !summaryLoading && displayed !== null && displayed !== '' && (
                      <>
                        <div className="history-summary-toolbar">
                          <button
                            className="history-summary-export-btn"
                            title="把当前会议总结以文本消息发送给所选 Teams 账号"
                            onClick={() => { void pushSummaryPdf(selectedMeeting.title || '会议总结', displayed, chosenRecipients) }}
                            disabled={teamsPushStatus === 'loading' || chosenRecipients.length === 0}
                          >
                            {teamsPushStatus === 'done' ? '已发送 ✓' : teamsPushStatus === 'error' ? '发送失败' : teamsPushStatus === 'loading' ? '发送中...' : `发送到 Teams（${chosenRecipients.length} 人）`}
                          </button>
                          <button className="history-summary-regen-btn" onClick={refetchSummary}>重新生成</button>
                        </div>
                        {renderTeamsSendResult(summaryPushResult)}
                        <div className="history-summary-body">
                          <div className="history-summary-edit-hint">可手动修改下方内容，再点「发送到 Teams」以文本消息发送给所选 Teams 账号</div>
                          <textarea
                            className="history-summary-edit"
                            value={displayed}
                            onChange={e => setSummaryText(e.target.value)}
                            spellCheck={false}
                          />
                        </div>
                      </>
                    )}

                    {/* ── 行动项 ── */}
                    {selectedSessionId && (
                      <div className="history-action-items">
                        <div className="history-action-items-header">
                          <span className="history-action-items-title">行动项</span>
                          <button
                            className="history-action-items-extract-btn"
                            onClick={() => { void handleExtractActionItems() }}
                            disabled={extractingActionItems}
                          >
                            {extractingActionItems ? '提取中...' : actionItems.length > 0 ? '重新提取' : 'AI 提取行动项'}
                          </button>
                        </div>
                        {actionItemsLoading && (
                          <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>
                        )}
                        {!actionItemsLoading && actionItems.length === 0 && (
                          <div className="history-action-items-empty">暂无行动项，点击"AI 提取行动项"从会议记录中提取</div>
                        )}
                        {!actionItemsLoading && actionItems.length > 0 && (
                          <ul className="history-action-items-list">
                            {actionItems.map(item => (
                              <li key={item.id} className={`history-action-item${item.status === 'done' ? ' done' : ''}`}>
                                <button
                                  className="history-action-item-check"
                                  onClick={() => { void handleToggleActionItem(item) }}
                                  title={item.status === 'done' ? '标记为未完成' : '标记为完成'}
                                >
                                  {item.status === 'done' ? '✓' : '○'}
                                </button>
                                <div className="history-action-item-body">
                                  {item.assignee && <span className="history-action-item-assignee">【{item.assignee}】</span>}
                                  <span className="history-action-item-content">{item.content}</span>
                                  {item.deadline && <span className="history-action-item-deadline">截止：{item.deadline}</span>}
                                </div>
                              </li>
                            ))}
                          </ul>
                        )}
                      </div>
                    )}
                  </div>
                )
              })()}

              {/* ── 整场录音 Tab ── */}
              {activeTab === 'audio' && (
                <div className="history-tab-content">
                  <div className="history-audio-toolbar">
                    <input
                      className="history-audio-search"
                      placeholder="搜索当前会议录音名称..."
                      value={audioKeyword}
                      onChange={e => setAudioKeyword(e.target.value)}
                    />
                  </div>
                  {audioLoading && (
                    <div className="history-summary-loading"><span className="history-summary-spinner" />加载中...</div>
                  )}
                  {!audioLoading && audioRecords.length === 0 && (
                    <div className="si-tri-empty">
                      {audioKeyword ? '无匹配录音' : '暂无整场会议录音（同传结束后自动生成）'}
                    </div>
                  )}
                  {!audioLoading && audioRecords.length > 0 && (
                    <ul className="history-audio-list">
                      {audioRecords.map(rec => (
                        <li key={rec.id} className="history-audio-item">
                          <div className="history-audio-meta">
                            {audioRenameId === rec.id ? (
                              <input
                                className="history-audio-rename-input"
                                autoFocus
                                value={audioRenameDraft}
                                onChange={e => setAudioRenameDraft(e.target.value)}
                                onKeyDown={e => {
                                  if (e.key === 'Enter') void handleAudioRenameCommit(rec.id)
                                  if (e.key === 'Escape') setAudioRenameId(null)
                                }}
                                onBlur={() => void handleAudioRenameCommit(rec.id)}
                              />
                            ) : (
                              <span
                                className="history-audio-name"
                                title="双击重命名"
                                onDoubleClick={() => { setAudioRenameId(rec.id); setAudioRenameDraft(rec.name) }}
                              >{rec.name}</span>
                            )}
                            <span className="history-audio-info">
                              {formatDuration(rec.durationMs)} · {formatBytes(rec.fileSizeBytes)} · {rec.createTime?.slice(0, 16).replace('T', ' ')}
                            </span>
                          </div>
                          <audio
                            className="history-audio-player"
                            controls
                            preload="none"
                            src={getAudioDownloadUrl(rec.id)}
                          />
                          <div className="history-audio-actions">
                            <button
                              className="history-audio-btn"
                              onClick={() => void handleAudioDownload(rec)}
                              disabled={downloadingAudioId === rec.id}
                            >{downloadingAudioId === rec.id ? '下载中...' : '下载'}</button>
                            <button
                              className="history-audio-btn"
                              onClick={() => { setAudioRenameId(rec.id); setAudioRenameDraft(rec.name) }}
                            >重命名</button>
                            <button
                              className="history-audio-btn history-audio-btn--danger"
                              onClick={() => void handleAudioDelete(rec.id)}
                            >删除</button>
                          </div>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </>
          )}
        </section>
      </main>
    </div>
  )
}
