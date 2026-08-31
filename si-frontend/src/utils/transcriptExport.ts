import type { TranscriptGroup } from './transcriptGrouping'

const LOCAL_DATE_TIME_PATTERN = /^\d{4}-\d{2}-\d{2}[T ](\d{2}):(\d{2})(?::(\d{2}))?(?:\.\d+)?$/

const escapeHtml = (text: string) =>
  text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;')

export const formatTranscriptTimestamp = (value?: string | number) => {
  if (typeof value === 'number') {
    if (!Number.isFinite(value) || value <= 0) return ''
    return new Intl.DateTimeFormat('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23',
    }).format(new Date(value))
  }

  const normalized = value?.trim()
  if (!normalized) return ''

  const localMatch = normalized.match(LOCAL_DATE_TIME_PATTERN)
  if (localMatch) {
    return `${localMatch[1]}:${localMatch[2]}:${localMatch[3] || '00'}`
  }

  const parsed = new Date(normalized)
  if (Number.isNaN(parsed.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hourCycle: 'h23',
  }).format(parsed)
}

export const buildTranscriptWordHtml = (groups: TranscriptGroup[]) => {
  const body = groups.map(group => {
    const speaker = group.speakerName || group.speakerId || ''
    const speakerHtml = speaker
      ? `<p style="font-weight:bold;color:#555;font-size:10.5pt;margin:0 0 2px 0;">${escapeHtml(speaker)}</p>`
      : ''
    const timestamp = formatTranscriptTimestamp(group.speechStartAtMs ?? group.createTime)
    const timestampHtml = timestamp
      ? `<span style="font-size:9pt;color:#667085;margin-right:8px;white-space:nowrap;">[${escapeHtml(timestamp)}]</span>`
      : ''
    return `
    <div style="margin-bottom:12px;">
      ${speakerHtml}
      <p style="margin:0 0 2px 0;">${timestampHtml}<span style="font-size:12pt;color:#111;">${escapeHtml(group.sourceText)}</span></p>
    </div>`
  }).join('')

  return `<html><head><meta charset="utf-8"/></head>
    <body style="font-family:Microsoft YaHei,Arial,sans-serif;line-height:1.8;font-size:12pt;">${body}</body></html>`
}
