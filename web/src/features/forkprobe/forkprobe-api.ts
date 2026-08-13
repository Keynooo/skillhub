import { fetchJson, WEB_API_PREFIX } from '@/api/client'

// --- Types ---

export interface RecommendedSkill {
  coordinate: string // "baseline" | "catalog:id" | "namespace/slug"
  name: string
  namespace: string
  reasonZh: string
  domain: string
  source: string
  stars: number
}

export interface RecommendResponse {
  candidates: RecommendedSkill[]
}

export interface CompareRequest {
  taskDescription: string
  skillCoordinates: string[]
}

export interface CompareResponse {
  comparisonId: string
  status: string
  createdAt: string
}

export interface CandidateResult {
  skillCoordinate: string
  skillName: string
  output: string | null
  tokensUsed: number
  latencySeconds: number
  /** null = not verified yet, true = applied, false = not applied */
  skillApplied: boolean | null
  appliedReason: string | null
  error: string | null
}

export interface ReviewScore {
  label: string
  score: number
}

export interface SkillReview {
  coordinate: string
  overall: number
  dimensions: ReviewScore[]
}

export interface ReviewResult {
  winnerCoordinate: string
  winnerReason: string
  scores: SkillReview[]
}

export interface ComparisonStatusResponse {
  comparisonId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  results: CandidateResult[]
  error: string | null
  startedAt: string | null
  completedAt: string | null
  /** Independent AI judge verdict — null until the review pass finishes (or if it failed). */
  review: ReviewResult | null
}

export interface ForkprobeConfig {
  maxSkills: number
  maxSkillsCap: number
  apiKeyConfigured: boolean
  catalogDomains: string[]
}

// --- API functions ---

const BASE = `${WEB_API_PREFIX}/forkprobe`

export async function recommendSkills(
  taskDescription: string,
  maxCandidates = 5,
): Promise<RecommendResponse> {
  return fetchJson<RecommendResponse>(`${BASE}/recommend`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskDescription, maxCandidates }),
  })
}

export async function startComparison(
  taskDescription: string,
  skillCoordinates: string[],
): Promise<CompareResponse> {
  return fetchJson<CompareResponse>(`${BASE}/compare`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskDescription, skillCoordinates }),
  })
}

export async function getComparisonStatus(
  comparisonId: string,
): Promise<ComparisonStatusResponse> {
  return fetchJson<ComparisonStatusResponse>(`${BASE}/compare/${encodeURIComponent(comparisonId)}`)
}

export async function getForkprobeConfig(): Promise<ForkprobeConfig> {
  return fetchJson<ForkprobeConfig>(`${BASE}/config`)
}
