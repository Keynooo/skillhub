import { describe, expect, it, vi } from 'vitest'

// forkprobe-api imports @/api/client, which pulls in i18n and ApiError; mock them
// the same way client.test.ts does before importing the module under test.
vi.mock('@/i18n/config', () => ({
  default: { resolvedLanguage: 'en' },
}))

vi.mock('@/shared/lib/api-error', () => ({
  ApiError: class ApiError extends Error {
    status: number
    serverMessage?: string
    constructor(message: string, status: number, serverMessage?: string) {
      super(message)
      this.status = status
      this.serverMessage = serverMessage
    }
  },
}))

import { resolveSkillLink, type CandidateResult } from './forkprobe-api'

function candidate(skillCoordinate: string, sourceUrl: string | null = null): CandidateResult {
  return {
    skillCoordinate,
    skillName: 'candidate',
    output: null,
    tokensUsed: 0,
    latencySeconds: 0,
    skillApplied: null,
    appliedReason: null,
    error: null,
    sourceUrl,
  }
}

describe('resolveSkillLink', () => {
  it('returns null for baseline', () => {
    expect(resolveSkillLink(candidate('baseline'))).toBeNull()
  })

  it('returns null for empty / unknown coordinate', () => {
    expect(resolveSkillLink(candidate(''))).toBeNull()
    expect(resolveSkillLink(candidate('just-one-part'))).toBeNull()
  })

  it('returns external link for catalog skill with sourceUrl', () => {
    expect(resolveSkillLink(candidate('catalog:academic-writing', 'https://github.com/x/y'))).toEqual({
      kind: 'external',
      href: 'https://github.com/x/y',
    })
  })

  it('returns null for catalog skill without sourceUrl', () => {
    expect(resolveSkillLink(candidate('catalog:academic-writing'))).toBeNull()
  })

  it('returns detail link for namespace/slug', () => {
    expect(resolveSkillLink(candidate('global/academic-writing'))).toEqual({
      kind: 'detail',
      namespace: 'global',
      slug: 'academic-writing',
    })
  })

  it('returns detail link even when sourceUrl is also present (SkillHub skill)', () => {
    expect(resolveSkillLink(candidate('team-x/skill-y', 'https://github.com/ignored'))).toEqual({
      kind: 'detail',
      namespace: 'team-x',
      slug: 'skill-y',
    })
  })
})
