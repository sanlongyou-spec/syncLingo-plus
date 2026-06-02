import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react'

const BOTTOM_THRESHOLD_PX = 48

const isNearBottom = (element: HTMLElement) =>
  element.scrollHeight - element.scrollTop - element.clientHeight <= BOTTOM_THRESHOLD_PX

export function useSmartAutoScroll<T extends HTMLElement>(dependencies: DependencyList) {
  const scrollRef = useRef<T | null>(null)
  const pausedRef = useRef(false)
  const [isPaused, setIsPausedState] = useState(false)

  const setIsPaused = useCallback((paused: boolean) => {
    pausedRef.current = paused
    setIsPausedState(paused)
  }, [])

  const scrollToBottom = useCallback(() => {
    const element = scrollRef.current
    if (!element) return
    element.scrollTop = element.scrollHeight
    setIsPaused(false)
  }, [setIsPaused])

  const handleScroll = useCallback(() => {
    const element = scrollRef.current
    if (!element) return
    const nextPaused = !isNearBottom(element)
    if (nextPaused !== pausedRef.current) {
      setIsPaused(nextPaused)
    }
  }, [setIsPaused])

  useEffect(() => {
    const element = scrollRef.current
    if (!element) return
    element.addEventListener('scroll', handleScroll, { passive: true })
    handleScroll()
    return () => element.removeEventListener('scroll', handleScroll)
  }, [handleScroll])

  useEffect(() => {
    const element = scrollRef.current
    if (!element) return
    if (!pausedRef.current || isNearBottom(element)) {
      scrollToBottom()
    }
  }, dependencies)

  return {
    scrollRef,
    isPaused,
    scrollToBottom,
  }
}
