import assert from 'node:assert/strict'
import test from 'node:test'

import { buildTranscriptWordHtml, formatTranscriptTimestamp } from '../src/utils/transcriptExport.ts'
import { buildTranscriptGroups } from '../src/utils/transcriptGrouping.ts'
import type { InterpretationResultItem } from '../src/types/index.ts'

const result = (overrides: Partial<InterpretationResultItem>): InterpretationResultItem => ({
  id: 1,
  sessionId: 'session-1',
  sourceText: '会议现在开始。',
  translatedText: 'The meeting starts now.',
  ...overrides,
})

test('grouped multilingual rows retain the earliest source record time', () => {
  const groups = buildTranscriptGroups([
    result({ id: 11, targetLang: 'en-US', speechStartAtMs: 1_788_142_513_000, createTime: '2026-08-31T10:15:15' }),
    result({ id: 12, targetLang: 'id-ID', speechStartAtMs: 1_788_142_511_000, createTime: '2026-08-31T10:15:13' }),
  ])

  assert.equal(groups.length, 1)
  assert.equal(groups[0].speechStartAtMs, 1_788_142_511_000)
  assert.equal(groups[0].createTime, '2026-08-31T10:15:13')
  assert.equal(groups[0].translations.length, 2)
})

test('local backend timestamps are exported as stable hour-minute-second values', () => {
  assert.equal(formatTranscriptTimestamp('2026-08-31T10:15:11.123'), '10:15:11')
  assert.equal(formatTranscriptTimestamp('2026-08-31 18:06'), '18:06:00')
})

test('Word export renders a smaller timestamp beside escaped source text', () => {
  const speechStartAtMs = new Date(2026, 7, 31, 10, 15, 11).getTime()
  const html = buildTranscriptWordHtml([{
    id: 11,
    sourceText: '预算 < 计划 & 需要复核',
    speakerName: 'Guest <1>',
    speechStartAtMs,
    createTime: '2026-08-31T10:15:15',
    translations: [],
  }])

  assert.match(html, /font-size:9pt[^>]*>\[10:15:11\]<\/span>/)
  assert.match(html, /font-size:12pt[^>]*>预算 &lt; 计划 &amp; 需要复核<\/span>/)
  assert.match(html, /Guest &lt;1&gt;/)
})

test('legacy rows without a valid timestamp export source text without a placeholder', () => {
  const html = buildTranscriptWordHtml([{
    id: 12,
    sourceText: '旧数据仍然可以导出。',
    createTime: 'invalid-time',
    translations: [],
  }])

  assert.doesNotMatch(html, /font-size:9pt/)
  assert.doesNotMatch(html, /\[--:--:--\]/)
  assert.match(html, /旧数据仍然可以导出。/)
})
