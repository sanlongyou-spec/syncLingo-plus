import type { UserSummaryRequirements } from '../types'

export const MAX_SUMMARY_REQUIREMENTS_LENGTH = 4000

export const LEGACY_SHARED_SUMMARY_STORAGE_KEYS = [
  'si_summary_requirements',
  'si_summary_default_recipients',
  'si_speaker_summary_requirements',
  'si_meeting_summary_requirements',
] as const

export type UserSummaryPreferenceState = {
  recipients: string[]
  meetingSummaryRequirements: string
  speakerSummaryRequirements: string
}

export const buildUserSummaryPreferenceState = (
  recipients?: string[] | null,
  requirements?: UserSummaryRequirements | null,
): UserSummaryPreferenceState => {
  const uniqueRecipients = new Map<string, string>()
  for (const value of recipients ?? []) {
    const email = value?.trim()
    if (!email) continue
    const key = email.toLowerCase()
    if (!uniqueRecipients.has(key)) uniqueRecipients.set(key, email)
  }

  return {
    recipients: Array.from(uniqueRecipients.values()),
    meetingSummaryRequirements: requirements?.meetingSummaryRequirements ?? '',
    speakerSummaryRequirements: requirements?.speakerSummaryRequirements ?? '',
  }
}

export const clearLegacySharedSummaryStorage = (
  storage: Pick<Storage, 'removeItem'>,
) => {
  for (const key of LEGACY_SHARED_SUMMARY_STORAGE_KEYS) storage.removeItem(key)
}
