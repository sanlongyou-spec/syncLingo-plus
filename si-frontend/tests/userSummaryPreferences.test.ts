import assert from 'node:assert/strict'
import test from 'node:test'

import {
  buildUserSummaryPreferenceState,
  clearLegacySharedSummaryStorage,
  LEGACY_SHARED_SUMMARY_STORAGE_KEYS,
} from '../src/utils/userSummaryPreferences.ts'

test('different accounts keep independent summary settings', () => {
  const accountA = buildUserSummaryPreferenceState(
    ['a@example.com'],
    {
      meetingSummaryRequirements: '账号 A 的会议总结要求',
      speakerSummaryRequirements: '账号 A 的发言摘要要求',
    },
  )
  const accountB = buildUserSummaryPreferenceState([], {
    meetingSummaryRequirements: '',
    speakerSummaryRequirements: '',
  })

  assert.deepEqual(accountA.recipients, ['a@example.com'])
  assert.equal(accountA.meetingSummaryRequirements, '账号 A 的会议总结要求')
  assert.deepEqual(accountB, {
    recipients: [],
    meetingSummaryRequirements: '',
    speakerSummaryRequirements: '',
  })
})

test('empty backend settings stay empty instead of using browser fallback values', () => {
  assert.deepEqual(buildUserSummaryPreferenceState(null, null), {
    recipients: [],
    meetingSummaryRequirements: '',
    speakerSummaryRequirements: '',
  })
})

test('recipient normalization trims blanks and de-duplicates email casing', () => {
  const state = buildUserSummaryPreferenceState([
    ' Alice@Example.com ',
    '',
    'alice@example.com',
    'bob@example.com',
  ], null)

  assert.deepEqual(state.recipients, ['Alice@Example.com', 'bob@example.com'])
})

test('legacy shared browser keys are removed without migration', () => {
  const removed: string[] = []
  clearLegacySharedSummaryStorage({ removeItem: key => removed.push(key) })

  assert.deepEqual(removed, [...LEGACY_SHARED_SUMMARY_STORAGE_KEYS])
})
