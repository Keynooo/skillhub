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
  /** GitHub source URL for catalog skills; null for SkillHub skills / baseline. */
  sourceUrl: string | null
}

export interface RecommendResponse {
  candidates: RecommendedSkill[]
}

export interface CompareRequest {
  taskDescription: string
  skillCoordinates: string[]
  /** Optional per-run provider id. Omit/empty to use the configured default model. */
  provider?: string
}

export interface CompareResponse {
  comparisonId: string
  status: string
  createdAt: string
}

export interface ForkprobeOutputFile {
  name: string
  sizeBytes: number
  contentType: string
  contentBase64: string
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
  /** GitHub source URL for catalog skills; null for SkillHub skills / baseline. */
  sourceUrl: string | null
  /** Deliverable files the sandbox run wrote to /output (base64-encoded). */
  files?: ForkprobeOutputFile[]
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
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  results: CandidateResult[]
  error: string | null
  startedAt: string | null
  completedAt: string | null
  /** Independent AI judge verdict — null until the review pass finishes (or if it failed). */
  review: ReviewResult | null
}

export interface ForkprobeProvider {
  id: string
  model: string
}

export interface ForkprobeConfig {
  maxSkills: number
  maxSkillsCap: number
  apiKeyConfigured: boolean
  /** The deployment's default model name (e.g. deepseek-v4-pro). */
  defaultModel: string
  /** Alternate LLM providers the user can select per run (empty model = unknown). */
  providers: ForkprobeProvider[]
}

export interface ComparisonHistoryItem {
  comparisonId: string
  taskDescription: string
  status: 'COMPLETED' | 'FAILED' | 'CANCELLED'
  /** null = deployment default provider */
  provider: string | null
  skillCount: number
  createdAt: string | null
  completedAt: string | null
}

// --- Pipeline (编排) ---

export interface PipelineResponse {
  pipelineId: string
  status: string
  createdAt: string
}

export interface PipelineStageResult {
  index: number
  skillCoordinate: string
  skillName: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED'
  output: string | null
  tokensUsed: number
  latencySeconds: number
  /** null = not verified yet, true = applied, false = not applied */
  skillApplied: boolean | null
  appliedReason: string | null
  error: string | null
  /** GitHub source URL for catalog skills; null for SkillHub skills / baseline. */
  sourceUrl: string | null
  /** Deliverable files the sandbox run wrote to /output (base64-encoded). */
  files?: ForkprobeOutputFile[]
  /** Truncated preview of what this stage received as input (task or prior stage output). */
  inputPreview: string | null
}

export interface PipelineLaneResult {
  /** 0-based lane number (0, 1, 2). */
  index: number
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'SKIPPED'
  stages: PipelineStageResult[]
}

export interface PipelineStatusResponse {
  pipelineId: string
  status: 'PENDING' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  lanes: PipelineLaneResult[]
  error: string | null
  startedAt: string | null
  completedAt: string | null
}

export interface PipelineHistoryItem {
  pipelineId: string
  taskDescription: string
  status: 'COMPLETED' | 'FAILED' | 'CANCELLED'
  /** null = deployment default provider */
  provider: string | null
  stageCount: number
  createdAt: string | null
  completedAt: string | null
}

// --- Link resolution ---

export type SkillLinkTarget =
  | { kind: 'detail'; namespace: string; slug: string }
  | { kind: 'external'; href: string }
  | null

/**
 * Resolve the follow-up action for a comparison candidate:
 * - `baseline` or unknown → null (no page)
 * - `catalog:<id>` + sourceUrl → external GitHub link
 * - `namespace/slug` → SkillHub detail page
 */
export function resolveSkillLink(result: CandidateResult): SkillLinkTarget {
  const coord = result.skillCoordinate
  if (!coord || coord === 'baseline') return null
  if (coord.startsWith('catalog:')) {
    return result.sourceUrl ? { kind: 'external', href: result.sourceUrl } : null
  }
  const parts = coord.split('/')
  if (parts.length === 2 && parts[0] && parts[1]) {
    return { kind: 'detail', namespace: parts[0], slug: parts[1] }
  }
  return null
}

/**
 * Resolve the follow-up action for a recommended skill (pre-comparison) so the
 * user can open its detail page / source before committing to the comparison.
 * Same coordinate conventions as {@link resolveSkillLink}, but keyed off the
 * recommendation's own `sourceUrl` field.
 */
export function resolveRecommendedSkillLink(skill: RecommendedSkill): SkillLinkTarget {
  const coord = skill.coordinate
  if (!coord || coord === 'baseline') return null
  if (coord.startsWith('catalog:')) {
    return skill.sourceUrl ? { kind: 'external', href: skill.sourceUrl } : null
  }
  const parts = coord.split('/')
  if (parts.length === 2 && parts[0] && parts[1]) {
    return { kind: 'detail', namespace: parts[0], slug: parts[1] }
  }
  return null
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
  provider?: string,
): Promise<CompareResponse> {
  return fetchJson<CompareResponse>(`${BASE}/compare`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskDescription, skillCoordinates, provider }),
  })
}

export async function getComparisonStatus(
  comparisonId: string,
): Promise<ComparisonStatusResponse> {
  return fetchJson<ComparisonStatusResponse>(`${BASE}/compare/${encodeURIComponent(comparisonId)}`)
}

export async function cancelComparison(
  comparisonId: string,
): Promise<ComparisonStatusResponse> {
  return fetchJson<ComparisonStatusResponse>(
    `${BASE}/compare/${encodeURIComponent(comparisonId)}/cancel`,
    { method: 'POST' },
  )
}

export async function getForkprobeConfig(): Promise<ForkprobeConfig> {
  return fetchJson<ForkprobeConfig>(`${BASE}/config`)
}

export async function getComparisonHistory(
  limit = 20,
): Promise<ComparisonHistoryItem[]> {
  return fetchJson<ComparisonHistoryItem[]>(`${BASE}/history?limit=${limit}`)
}

export async function getComparisonHistoryDetail(
  comparisonId: string,
): Promise<ComparisonStatusResponse> {
  return fetchJson<ComparisonStatusResponse>(
    `${BASE}/history/${encodeURIComponent(comparisonId)}`,
  )
}

// --- Pipeline API functions ---

export async function startPipeline(
  taskDescription: string,
  lanes: string[][],
  provider?: string,
): Promise<PipelineResponse> {
  return fetchJson<PipelineResponse>(`${BASE}/pipeline`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskDescription, lanes, provider }),
  })
}

export async function startAutopilotPipeline(
  taskDescription: string,
  skillCoordinates: string[],
  provider?: string,
): Promise<PipelineResponse> {
  return fetchJson<PipelineResponse>(`${BASE}/pipeline/autopilot`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ taskDescription, skillCoordinates, provider }),
  })
}

export async function getPipelineStatus(
  pipelineId: string,
): Promise<PipelineStatusResponse> {
  return fetchJson<PipelineStatusResponse>(
    `${BASE}/pipeline/${encodeURIComponent(pipelineId)}`,
  )
}

export async function cancelPipeline(
  pipelineId: string,
): Promise<PipelineStatusResponse> {
  return fetchJson<PipelineStatusResponse>(
    `${BASE}/pipeline/${encodeURIComponent(pipelineId)}/cancel`,
    { method: 'POST' },
  )
}

export async function getPipelineHistory(
  limit = 20,
): Promise<PipelineHistoryItem[]> {
  return fetchJson<PipelineHistoryItem[]>(`${BASE}/pipeline/history?limit=${limit}`)
}

export async function getPipelineHistoryDetail(
  pipelineId: string,
): Promise<PipelineStatusResponse> {
  return fetchJson<PipelineStatusResponse>(
    `${BASE}/pipeline/history/${encodeURIComponent(pipelineId)}`,
  )
}
