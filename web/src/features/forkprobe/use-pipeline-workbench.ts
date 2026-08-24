import { useState, useCallback, useMemo, useEffect } from 'react'
import {
  useForkprobeConfig,
  useForkprobeRecommend,
  useStartPipeline,
  usePipelineStatus,
  useCancelPipeline,
} from './use-forkprobe-queries'
import type { RecommendedSkill, PipelineStatusResponse } from './forkprobe-api'

const ACTIVE_RUN_KEY = 'forkprobe.pipeline.active'
const SELECTING_DRAFT_KEY = 'forkprobe.pipeline.selecting'

/** Must stay in sync with the backend's PipelineRequest @Size(max=3). */
export const MAX_LANES = 3

/** A new workbench starts with two lanes: one for skills, one native reference. */
const INITIAL_LANES: string[][] = [[], []]

interface ActiveRun {
  pipelineId: string
  taskDescription: string
  provider: string
  lanes: string[][]
}

interface SelectingDraft {
  taskDescription: string
  provider: string
  lanes: string[][]
  recommendations: RecommendedSkill[]
  manualSkills: RecommendedSkill[]
}

function readActiveRun(): ActiveRun | null {
  try {
    const raw = sessionStorage.getItem(ACTIVE_RUN_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as ActiveRun | null
    if (!parsed || typeof parsed.pipelineId !== 'string') return null
    return parsed
  } catch {
    return null
  }
}

function writeActiveRun(run: ActiveRun) {
  try {
    sessionStorage.setItem(ACTIVE_RUN_KEY, JSON.stringify(run))
  } catch {
    // storage full/unavailable — non-fatal
  }
}

function clearActiveRun() {
  try {
    sessionStorage.removeItem(ACTIVE_RUN_KEY)
  } catch {
    // ignore
  }
}

function readSelectingDraft(): SelectingDraft | null {
  try {
    const raw = sessionStorage.getItem(SELECTING_DRAFT_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as SelectingDraft | null
    if (!parsed || typeof parsed.taskDescription !== 'string') return null
    return parsed
  } catch {
    return null
  }
}

function writeSelectingDraft(draft: SelectingDraft) {
  try {
    sessionStorage.setItem(SELECTING_DRAFT_KEY, JSON.stringify(draft))
  } catch {
    // storage full/unavailable — non-fatal
  }
}

function clearSelectingDraft() {
  try {
    sessionStorage.removeItem(SELECTING_DRAFT_KEY)
  } catch {
    // ignore
  }
}

/** Normalize a persisted lanes array to a valid 1..MAX_LANES list of lanes. */
function normalizeLanes(lanes: unknown): string[][] {
  if (!Array.isArray(lanes)) return INITIAL_LANES.map((l) => [...l])
  const out: string[][] = []
  for (const lane of lanes.slice(0, MAX_LANES)) {
    if (Array.isArray(lane) && lane.every((c: unknown) => typeof c === 'string')) {
      out.push(lane as string[])
    }
  }
  if (out.length === 0) return INITIAL_LANES.map((l) => [...l])
  return out
}

export type PanelState = 'IDLE' | 'RECOMMENDING' | 'SELECTING' | 'RUNNING' | 'COMPLETED'

export interface UsePipelineWorkbenchReturn {
  // State
  panelState: PanelState
  taskDescription: string
  setTaskDescription: (value: string) => void
  provider: string
  setProvider: (value: string) => void
  lanes: string[][]
  pipelineId: string | null
  error: string | null

  // Handlers
  handleAddStage: (laneIndex: number, skill: RecommendedSkill) => void
  handleRemoveStage: (laneIndex: number, stageIndex: number) => void
  handleMoveStage: (fromLaneIndex: number, fromStageIndex: number, toLaneIndex: number) => void
  handleAddLane: () => void
  handleRemoveLane: (laneIndex: number) => void
  handleGetRecommendations: () => void
  handleStartPipeline: () => Promise<void>
  handleCancelPipeline: () => Promise<void>
  handleReset: () => void
  handleClearAll: () => void
  isStartingPipeline: boolean
  isCancellingPipeline: boolean

  // Data
  config: ReturnType<typeof useForkprobeConfig>['data']
  recommendations: RecommendedSkill[]
  allSkills: RecommendedSkill[]
  statusData: PipelineStatusResponse | undefined

  // Derived
  maxStages: number
  apiKeyOk: boolean
}

/**
 * State machine for the forkprobe workbench: one task fanned out into up to
 * MAX_LANES parallel lanes (browser-style tabs). The user picks an unordered
 * pool of 0..5 skills per lane via click; the system auto-orders each lane into
 * a serial chain. An empty lane runs as the native/baseline reference.
 */
export function usePipelineWorkbench(): UsePipelineWorkbenchReturn {
  const { data: config } = useForkprobeConfig()

  const [restoredRun] = useState<ActiveRun | null>(() => readActiveRun())
  const [restoredDraft] = useState<SelectingDraft | null>(() => readSelectingDraft())

  const [panelState, setPanelState] = useState<PanelState>(() =>
    restoredRun ? 'RUNNING' : restoredDraft ? 'SELECTING' : 'IDLE',
  )
  const [taskDescription, setTaskDescription] = useState(
    () => restoredRun?.taskDescription ?? restoredDraft?.taskDescription ?? '',
  )
  const [provider, setProvider] = useState(
    () => restoredRun?.provider ?? restoredDraft?.provider ?? 'default',
  )
  const [lanes, setLanes] = useState<string[][]>(() => {
    if (restoredRun) return normalizeLanes(restoredRun.lanes)
    if (restoredDraft) return normalizeLanes(restoredDraft.lanes)
    return INITIAL_LANES.map((l) => [...l])
  })
  const [pipelineId, setPipelineId] = useState<string | null>(
    () => restoredRun?.pipelineId ?? null,
  )
  const [recommendTask, setRecommendTask] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [manualSkills, setManualSkills] = useState<RecommendedSkill[]>(
    () => restoredDraft?.manualSkills ?? [],
  )
  const restoredRecommendations = restoredDraft?.recommendations ?? []

  // Queries
  const recommendQuery = useForkprobeRecommend(
    recommendTask,
    panelState === 'RECOMMENDING',
  )
  const startPipelineMutation = useStartPipeline()
  const cancelPipelineMutation = useCancelPipeline()
  const statusQuery = usePipelineStatus(
    panelState === 'RUNNING' || panelState === 'COMPLETED' ? pipelineId : null,
  )

  const maxStages = config?.maxSkillsCap ?? 5
  const apiKeyOk = config?.apiKeyConfigured ?? true

  const recommendations = useMemo(
    () =>
      (recommendQuery.data?.candidates ?? restoredRecommendations).filter(
        (s) => s.coordinate !== 'baseline',
      ),
    [recommendQuery.data, restoredRecommendations],
  )

  // Merge recommendations + manually searched skills into the combined list,
  // deduplicated by coordinate (a skill can arrive from both sources).
  const allSkills = useMemo(() => {
    const seen = new Set<string>()
    const merged: RecommendedSkill[] = []
    for (const s of [...recommendations, ...manualSkills]) {
      if (s.coordinate === 'baseline' || seen.has(s.coordinate)) continue
      seen.add(s.coordinate)
      merged.push(s)
    }
    return merged
  }, [recommendations, manualSkills])

  // --- Side effects for automatic transitions ---

  // RECOMMENDING → SELECTING when query succeeds
  useEffect(() => {
    if (panelState === 'RECOMMENDING' && recommendQuery.isSuccess) {
      setPanelState('SELECTING')
    }
  }, [panelState, recommendQuery.isSuccess])

  // RUNNING → COMPLETED when pipeline reaches a terminal state
  const statusData = statusQuery.data
  useEffect(() => {
    if (panelState === 'RUNNING' && statusData) {
      if (
        statusData.status === 'COMPLETED' ||
        statusData.status === 'FAILED' ||
        statusData.status === 'CANCELLED'
      ) {
        setPanelState('COMPLETED')
        clearActiveRun()
      }
    }
  }, [panelState, statusData])

  // If the restored pipeline no longer exists (backend restart / TTL expiry),
  // drop the stale run and return to idle instead of polling a 404 forever.
  useEffect(() => {
    if (panelState === 'RUNNING' && statusQuery.isError) {
      clearActiveRun()
      setPanelState('IDLE')
      setPipelineId(null)
    }
  }, [panelState, statusQuery.isError])

  // Persist the SELECTING draft so navigating away and back keeps the lanes.
  useEffect(() => {
    if (panelState === 'SELECTING' && taskDescription.trim().length > 0) {
      writeSelectingDraft({
        taskDescription,
        provider,
        lanes,
        recommendations,
        manualSkills,
      })
    }
  }, [panelState, taskDescription, provider, lanes, recommendations, manualSkills])

  // --- Handlers ---

  const handleGetRecommendations = useCallback(() => {
    if (!taskDescription.trim() || taskDescription.trim().length < 3) return
    setError(null)
    setPanelState('RECOMMENDING')
    setRecommendTask(taskDescription.trim())
  }, [taskDescription])

  const handleAddStage = useCallback(
    (laneIndex: number, skill: RecommendedSkill) => {
      // "baseline" is not a pickable skill — an empty lane already IS the baseline.
      if (skill.coordinate === 'baseline') return
      setManualSkills((prev) =>
        prev.some((s) => s.coordinate === skill.coordinate) ? prev : [...prev, skill],
      )
      setLanes((prev) => {
        const lane = prev[laneIndex]
        // Toggle: clicking a skill already in the target lane removes it.
        if (lane?.includes(skill.coordinate)) {
          return prev.map((l, i) => (i === laneIndex ? l.filter((c) => c !== skill.coordinate) : l))
        }
        if (!lane || lane.length >= maxStages) return prev
        return prev.map((l, i) => (i === laneIndex ? [...l, skill.coordinate] : l))
      })
      setPanelState((p) => (p === 'IDLE' ? 'SELECTING' : p))
    },
    [maxStages],
  )

  const handleRemoveStage = useCallback((laneIndex: number, stageIndex: number) => {
    setLanes((prev) =>
      prev.map((l, i) => (i === laneIndex ? l.filter((_, j) => j !== stageIndex) : l)),
    )
  }, [])

  const handleMoveStage = useCallback(
    (fromLaneIndex: number, fromStageIndex: number, toLaneIndex: number) => {
      setLanes((prev) => {
        if (fromLaneIndex === toLaneIndex) return prev
        const from = prev[fromLaneIndex]
        const to = prev[toLaneIndex]
        if (!from || !to) return prev
        const coord = from[fromStageIndex]
        if (!coord || to.includes(coord) || to.length >= maxStages) return prev
        const next = prev.map((l) => [...l])
        next[fromLaneIndex] = next[fromLaneIndex].filter((_, j) => j !== fromStageIndex)
        next[toLaneIndex] = [...next[toLaneIndex], coord]
        return next
      })
    },
    [maxStages],
  )

  /** Append an empty lane (browser-style "+ new tab"), capped at MAX_LANES. */
  const handleAddLane = useCallback(() => {
    setLanes((prev) => (prev.length >= MAX_LANES ? prev : [...prev, []]))
  }, [])

  /** Close a lane tab; keeps at least one lane. Caller handles tab re-selection. */
  const handleRemoveLane = useCallback((laneIndex: number) => {
    setLanes((prev) => (prev.length <= 1 ? prev : prev.filter((_, i) => i !== laneIndex)))
  }, [])

  /**
   * Start the pipeline run. Each lane (tab) runs as its own independent result
   * channel: the skills the user picked in that lane are executed in sequence,
   * and an empty lane runs as the native/baseline reference. One lane per channel,
   * so the number of result channels matches the number of tabs the user created.
   */
  const handleStartPipeline = useCallback(async () => {
    // A run needs at least one lane with content; empty lanes are kept as-is so
    // the backend surfaces them as baseline channels (keeping the tab count).
    const hasContent = lanes.some((l) => l.length > 0)
    if (!hasContent || !taskDescription.trim()) return
    setError(null)

    try {
      const result = await startPipelineMutation.mutateAsync({
        taskDescription: taskDescription.trim(),
        lanes,
        provider: provider && provider !== 'default' ? provider : undefined,
      })
      setPipelineId(result.pipelineId)
      setPanelState('RUNNING')
      clearSelectingDraft()
      writeActiveRun({
        pipelineId: result.pipelineId,
        taskDescription: taskDescription.trim(),
        provider,
        lanes,
      })
    } catch (e) {
      setError(e instanceof Error ? e.message : '编排启动失败')
    }
  }, [lanes, taskDescription, provider, startPipelineMutation])

  const handleCancelPipeline = useCallback(async () => {
    if (!pipelineId) return
    setError(null)
    try {
      await cancelPipelineMutation.mutateAsync(pipelineId)
    } catch (e) {
      setError(e instanceof Error ? e.message : '取消失败')
    }
  }, [pipelineId, cancelPipelineMutation])

  /** Fully clear the workbench back to the initial empty task input. */
  const handleClearAll = useCallback(() => {
    setPanelState('IDLE')
    setPipelineId(null)
    setTaskDescription('')
    setProvider('default')
    setLanes(INITIAL_LANES.map((l) => [...l]))
    setManualSkills([])
    setRecommendTask('')
    setError(null)
    clearActiveRun()
    clearSelectingDraft()
  }, [])

  /** After a finished run: back to SELECTING with the task kept, lanes cleared. */
  const handleReset = useCallback(() => {
    setPanelState('SELECTING')
    setPipelineId(null)
    setLanes(INITIAL_LANES.map((l) => [...l]))
    clearActiveRun()
    clearSelectingDraft()
  }, [])

  return {
    panelState,
    taskDescription,
    setTaskDescription,
    provider,
    setProvider,
    lanes,
    pipelineId,
    error,
    handleAddStage,
    handleRemoveStage,
    handleMoveStage,
    handleAddLane,
    handleRemoveLane,
    handleGetRecommendations,
    handleStartPipeline,
    handleCancelPipeline,
    handleReset,
    handleClearAll,
    isStartingPipeline: startPipelineMutation.isPending,
    isCancellingPipeline: cancelPipelineMutation.isPending,
    config,
    recommendations,
    allSkills,
    statusData,
    maxStages,
    apiKeyOk,
  }
}

