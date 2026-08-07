import type { InterpretationResultItem } from '../types'

const MERGE_LOOKBACK_GROUPS = 12
const MERGE_RECORD_ID_WINDOW = 20

export type TranscriptGroupTranslation = {
  text: string
  targetLang?: string
}

export type TranscriptGroup = {
  id: number
  sourceText: string
  sourceLang?: string
  speakerId?: string
  speakerName?: string
  translations: TranscriptGroupTranslation[]
}

const normalizeTextKey = (text?: string) => (text ?? '').trim().replace(/\s+/g, ' ')

const normalizeLangKey = (lang?: string) => (lang ?? '').trim().toLowerCase()

const normalizeSpeakerKey = (speakerId?: string, speakerName?: string) =>
  (speakerId || speakerName || '').trim().toLowerCase()

const sameOptionalKey = (left: string, right: string) => left === '' || right === '' || left === right

const hasTargetTranslation = (group: TranscriptGroup, targetLang?: string) => {
  const targetKey = normalizeLangKey(targetLang)
  return targetKey !== '' && group.translations.some(item => normalizeLangKey(item.targetLang) === targetKey)
}

const findRecentMergeIndex = (groups: TranscriptGroup[], item: InterpretationResultItem) => {
  const sourceKey = normalizeTextKey(item.sourceText)
  if (sourceKey === '') return -1

  const itemSourceLangKey = normalizeLangKey(item.sourceLang)
  const itemSpeakerKey = normalizeSpeakerKey(item.speakerId, item.speakerName)
  const start = Math.max(0, groups.length - MERGE_LOOKBACK_GROUPS)

  for (let i = groups.length - 1; i >= start; i -= 1) {
    const group = groups[i]
    if (normalizeTextKey(group.sourceText) !== sourceKey) continue
    if (!sameOptionalKey(normalizeLangKey(group.sourceLang), itemSourceLangKey)) continue
    if (!sameOptionalKey(normalizeSpeakerKey(group.speakerId, group.speakerName), itemSpeakerKey)) continue
    if (Math.abs(item.id - group.id) > MERGE_RECORD_ID_WINDOW) continue
    if (hasTargetTranslation(group, item.targetLang)) continue
    return i
  }
  return -1
}

export const buildTranscriptGroups = (results: InterpretationResultItem[]): TranscriptGroup[] => {
  const groups: TranscriptGroup[] = []

  for (const item of results) {
    const translation = item.translatedText
      ? { text: item.translatedText, targetLang: item.targetLang }
      : null
    const mergeIndex = findRecentMergeIndex(groups, item)

    if (mergeIndex >= 0) {
      const current = groups[mergeIndex]
      groups[mergeIndex] = {
        ...current,
        sourceLang: current.sourceLang || item.sourceLang,
        speakerId: current.speakerId || item.speakerId,
        speakerName: current.speakerName || item.speakerName,
        translations: translation ? [...current.translations, translation] : current.translations,
      }
      continue
    }

    groups.push({
      id: item.id,
      sourceText: item.sourceText || '',
      sourceLang: item.sourceLang,
      speakerId: item.speakerId,
      speakerName: item.speakerName,
      translations: translation ? [translation] : [],
    })
  }

  return groups
}
