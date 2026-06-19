import { useCallback, useEffect, useState } from 'react'
import {
  DEFAULT_TRANSCRIPT_FONT_SCALE,
  STORAGE_KEYS,
  TRANSCRIPT_FONT_SCALES,
} from '../constants'

/**
 * 同传文本字号 Hook。
 *
 * 使用界面与共享页共用同一个 localStorage 键，因此同一浏览器内两边字号保持一致；
 * 同时监听 storage 事件，在另一个标签页调整字号时实时同步。
 */
const ALLOWED_SCALES: number[] = TRANSCRIPT_FONT_SCALES.map(option => option.value)

function isAllowedScale(value: number): boolean {
  return Number.isFinite(value) && ALLOWED_SCALES.includes(value)
}

function readStoredScale(): number {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.TRANSCRIPT_FONT_SCALE)
    const parsed = raw === null ? NaN : Number(raw)
    return isAllowedScale(parsed) ? parsed : DEFAULT_TRANSCRIPT_FONT_SCALE
  } catch {
    return DEFAULT_TRANSCRIPT_FONT_SCALE
  }
}

export function useTranscriptFontScale() {
  const [scale, setScaleState] = useState<number>(readStoredScale)

  const setScale = useCallback((next: number) => {
    if (!isAllowedScale(next)) return
    setScaleState(next)
    try {
      localStorage.setItem(STORAGE_KEYS.TRANSCRIPT_FONT_SCALE, String(next))
    } catch {
      /* localStorage 不可用时仅保留内存中的字号 */
    }
  }, [])

  useEffect(() => {
    const handleStorage = (event: StorageEvent) => {
      if (event.key !== STORAGE_KEYS.TRANSCRIPT_FONT_SCALE || event.newValue === null) return
      const next = Number(event.newValue)
      if (isAllowedScale(next)) setScaleState(next)
    }
    window.addEventListener('storage', handleStorage)
    return () => window.removeEventListener('storage', handleStorage)
  }, [])

  return { scale, setScale }
}
