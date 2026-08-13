import { useState, useCallback, useMemo, useEffect } from 'react'
import {
  useForkprobeConfig,
  useForkprobeRecommend,
  useStartComparison,
  useForkprobeComparisonStatus,
} from './use-forkprobe-queries'
import type { PreselectedSkill } from './comparison-panel-context'
import type { RecommendedSkill } from './forkprobe-api'

export type PanelState = 'IDLE' | 'RECOMMENDING' | 'SELECTING' | 'RUNNING' | 'COMPLETED'

export interface UseForkprobeWorkbenchOptions {
  /** Skills to preselect (from skill-detail page navigation or similar-skills compare). */
  preselectedSkills?: PreselectedSkill[]
}

export interface UseForkprobeWorkbenchReturn {
  // State
  panelState: PanelState
  taskDescription: string
  setTaskDescription: (value: string) => void
  selectedSkills: Set<string>
  comparisonId: string | null
  error: string | null

  // Handlers
  handleToggleSkill: (coordinate: string) => void
  handleAddSkill: (skill: RecommendedSkill) => void
  handleGetRecommendations: () => void
  handleStartComparison: () => Promise<void>
  handleReset: () => void
  isStartingComparison: boolean

  // Data
  config: ReturnType<typeof useForkprobeConfig>['data']
  recommendations: RecommendedSkill[]
  allSkills: RecommendedSkill[]
  statusData: ReturnType<typeof useForkprobeComparisonStatus>['data']

  // Derived
  maxSelect: number
  apiKeyOk: boolean
}

/**
 * Shared state machine for the forkprobe comparison workflow.
 *
 * Used by both the slide-out {@link ComparisonPanel} and the full-screen
 * {@link ForkprobeWorkbenchPage}.
 */
export function useForkprobeWorkbench(
  options: UseForkprobeWorkbenchOptions = {},
): UseForkprobeWorkbenchReturn {
  const { preselectedSkills = [] } = options

  const { data: config } = useForkprobeConfig()

  const [panelState, setPanelState] = useState<PanelState>('IDLE')
  const [taskDescription, setTaskDescription] = useState('')
  const [selectedSkills, setSelectedSkills] = useState<Set<string>>(new Set())
  const [comparisonId, setComparisonId] = useState<string | null>(null)
  const [recommendTask, setRecommendTask] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [manualSkills, setManualSkills] = useState<RecommendedSkill[]>([])

  // Queries
  const recommendQuery = useForkprobeRecommend(
    recommendTask,
    panelState === 'RECOMMENDING',
  )
  const startComparisonMutation = useStartComparison()
  const statusQuery = useForkprobeComparisonStatus(
    panelState === 'RUNNING' || panelState === 'COMPLETED' ? comparisonId : null,
  )

  const maxSelect = config?.maxSkills ?? 3
  const apiKeyOk = config?.apiKeyConfigured ?? true

  const recommendations = useMemo(
    () => recommendQuery.data?.candidates ?? [],
    [recommendQuery.data],
  )

  // Merge preselected skills + manually searched skills into the combined list
  const allSkills = useMemo(() => {
    const merged = [...recommendations, ...manualSkills]
    for (const ps of preselectedSkills) {
      const alreadyPresent = merged.some((r) => r.coordinate === ps.coordinate)
      if (!alreadyPresent) {
        merged.push({
          coordinate: ps.coordinate,
          name: ps.name,
          namespace: ps.namespace,
          reasonZh: '当前浏览的技能',
          domain: 'selected',
          source: 'selected',
          stars: 0,
        })
      }
    }
    return merged
  }, [recommendations, manualSkills, preselectedSkills])

  // --- Side effects for automatic transitions ---

  // RECOMMENDING → SELECTING when query succeeds
  useEffect(() => {
    if (panelState === 'RECOMMENDING' && recommendQuery.isSuccess) {
      setPanelState('SELECTING')
    }
  }, [panelState, recommendQuery.isSuccess])

  // RUNNING → COMPLETED when comparison finishes
  const statusData = statusQuery.data
  useEffect(() => {
    if (panelState === 'RUNNING' && statusData) {
      if (statusData.status === 'COMPLETED' || statusData.status === 'FAILED') {
        setPanelState('COMPLETED')
      }
    }
  }, [panelState, statusData])

  // Preselection from skill detail page / similar-skills compare
  useEffect(() => {
    if (preselectedSkills.length > 0 && panelState === 'IDLE') {
      setSelectedSkills(
        new Set(preselectedSkills.map((ps) => ps.coordinate).slice(0, maxSelect)),
      )
    }
  }, [preselectedSkills, panelState, maxSelect])

  // --- Handlers ---

  const handleGetRecommendations = useCallback(() => {
    if (!taskDescription.trim() || taskDescription.trim().length < 3) return
    setError(null)
    setPanelState('RECOMMENDING')
    setRecommendTask(taskDescription.trim())
  }, [taskDescription])

  const handleToggleSkill = useCallback(
    (coordinate: string) => {
      setSelectedSkills((prev) => {
        const next = new Set(prev)
        if (next.has(coordinate)) {
          next.delete(coordinate)
        } else if (next.size < maxSelect) {
          next.add(coordinate)
        }
        return next
      })
    },
    [maxSelect],
  )

  const handleAddSkill = useCallback(
    (skill: RecommendedSkill) => {
      setManualSkills((prev) =>
        prev.some((s) => s.coordinate === skill.coordinate) ? prev : [...prev, skill],
      )
      setSelectedSkills((prev) => {
        if (prev.has(skill.coordinate) || prev.size >= maxSelect) return prev
        const next = new Set(prev)
        next.add(skill.coordinate)
        return next
      })
      // Surface the candidate list if the user searched straight from the idle state
      setPanelState((p) => (p === 'IDLE' ? 'SELECTING' : p))
    },
    [maxSelect],
  )

  const handleStartComparison = useCallback(async () => {
    if (selectedSkills.size === 0 || !taskDescription.trim()) return
    setError(null)

    try {
      const result = await startComparisonMutation.mutateAsync({
        taskDescription: taskDescription.trim(),
        skillCoordinates: Array.from(selectedSkills),
      })
      setComparisonId(result.comparisonId)
      setPanelState('RUNNING')
    } catch (e) {
      setError(e instanceof Error ? e.message : '启动对比失败')
    }
  }, [selectedSkills, taskDescription, startComparisonMutation])

  const handleReset = useCallback(() => {
    setPanelState('SELECTING')
    setComparisonId(null)
    setSelectedSkills(new Set())
  }, [])

  return {
    panelState,
    taskDescription,
    setTaskDescription,
    selectedSkills,
    comparisonId,
    error,
    handleToggleSkill,
    handleAddSkill,
    handleGetRecommendations,
    handleStartComparison,
    handleReset,
    isStartingComparison: startComparisonMutation.isPending,
    config,
    recommendations,
    allSkills,
    statusData: statusData ?? undefined,
    maxSelect,
    apiKeyOk,
  }
}
