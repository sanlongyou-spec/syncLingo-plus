import { type DependencyList, useCallback, useEffect, useRef, useState } from 'react'

/** Resume auto-scroll when within this many px of the bottom */
const BOTTOM_THRESHOLD_PX = 100
/** Only PAUSE auto-scroll if the user scrolls MORE than this many px above bottom */
const PAUSE_THRESHOLD_PX = 200

const isNearBottom = (element: HTMLElement) =>
  element.scrollHeight - element.scrollTop - element.clientHeight <= BOTTOM_THRESHOLD_PX

export function useSmartAutoScroll<T extends HTMLElement>(dependencies: DependencyList) {
  const scrollRef = useRef<T | null>(null)
  const pausedRef = useRef(false)
  /** Set to true during programmatic scrolls so handleScroll doesn't reset paused state */
  const programmaticRef = useRef(false)
  const [isPaused, setIsPausedState] = useState(false)

  const setIsPaused = useCallback((paused: boolean) => {
    pausedRef.current = paused
    setIsPausedState(paused)
  }, [])

  const scrollToBottom = useCallback(() => {
    const element = scrollRef.current
    if (!element) return
    programmaticRef.current = true
    element.scrollTop = element.scrollHeight
    setIsPaused(false)
    // Clear the guard after the browser has processed the programmatic scroll event
    requestAnimationFrame(() => {
      programmaticRef.current = false
    })
  }, [setIsPaused])

  const handleScroll = useCallback(() => {
    // Ignore scroll events that are triggered by our own scrollToBottom()
    if (programmaticRef.current) return
    const element = scrollRef.current
    if (!element) return
    const distFromBottom = element.scrollHeight - element.scrollTop - element.clientHeight
    // Use asymmetric thresholds: generous resume (100px), strict pause (200px)
    // This prevents false pausing when content grows just after a programmatic scroll
    const nextPaused = distFromBottom > PAUSE_THRESHOLD_PX
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
