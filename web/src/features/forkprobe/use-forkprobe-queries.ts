import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  recommendSkills,
  startComparison,
  getComparisonStatus,
  cancelComparison,
  getForkprobeConfig,
  getComparisonHistory,
  getComparisonHistoryDetail,
  startPipeline,
  startAutopilotPipeline,
  getPipelineStatus,
  cancelPipeline,
  getPipelineHistory,
  getPipelineHistoryDetail,
  type ComparisonStatusResponse,
  type CompareResponse,
  type PipelineStatusResponse,
  type PipelineResponse,
} from './forkprobe-api'

// --- Query keys ---

export const forkprobeKeys = {
  config: ['forkprobe', 'config'] as const,
  recommend: (taskDescription: string) =>
    ['forkprobe', 'recommend', taskDescription] as const,
  comparison: (comparisonId: string) =>
    ['forkprobe', 'comparison', comparisonId] as const,
  history: () => ['forkprobe', 'history'] as const,
  historyDetail: (comparisonId: string) =>
    ['forkprobe', 'history', comparisonId] as const,
  pipeline: (pipelineId: string) =>
    ['forkprobe', 'pipeline', pipelineId] as const,
  pipelineHistory: () => ['forkprobe', 'pipelineHistory'] as const,
  pipelineHistoryDetail: (pipelineId: string) =>
    ['forkprobe', 'pipelineHistory', pipelineId] as const,
}

// --- Config ---

export function useForkprobeConfig() {
  return useQuery({
    queryKey: forkprobeKeys.config,
    queryFn: getForkprobeConfig,
    staleTime: 5 * 60 * 1000, // 5 minutes
  })
}

// --- Recommendations ---

/**
 * Fetch skill recommendations for a task description.
 * Debounced at the call site (use a separate debounce hook before passing taskDescription).
 */
export function useForkprobeRecommend(taskDescription: string, enabled = true) {
  return useQuery({
    queryKey: forkprobeKeys.recommend(taskDescription),
    queryFn: () => recommendSkills(taskDescription),
    enabled: enabled && taskDescription.trim().length >= 3,
    staleTime: 60 * 1000, // 1 minute
  })
}

// --- Comparison run ---

/**
 * Mutation to start a new comparison run.
 */
export function useStartComparison() {
  const queryClient = useQueryClient()

  return useMutation<
    CompareResponse,
    Error,
    { taskDescription: string; skillCoordinates: string[]; provider?: string }
  >({
    mutationFn: ({ taskDescription, skillCoordinates, provider }) =>
      startComparison(taskDescription, skillCoordinates, provider),
    onSuccess: () => {
      // Invalidate config in case limits changed
      queryClient.invalidateQueries({ queryKey: forkprobeKeys.config })
    },
  })
}

/**
 * Poll comparison status until a terminal state (COMPLETED / FAILED / CANCELLED).
 * Pass `null` for comparisonId when no comparison is running.
 */
export function useForkprobeComparisonStatus(comparisonId: string | null) {
  return useQuery<ComparisonStatusResponse>({
    queryKey: forkprobeKeys.comparison(comparisonId!),
    queryFn: () => getComparisonStatus(comparisonId!),
    enabled: !!comparisonId,
    refetchInterval: (query) => {
      const data = query.state.data
      if (
        data?.status === 'COMPLETED' ||
        data?.status === 'FAILED' ||
        data?.status === 'CANCELLED'
      ) {
        return false
      }
      return 2000 // poll every 2 seconds
    },
  })
}

/**
 * Cancel an in-flight comparison run.
 */
export function useCancelComparison() {
  const queryClient = useQueryClient()

  return useMutation<ComparisonStatusResponse, Error, string>({
    mutationFn: (comparisonId) => cancelComparison(comparisonId),
    onSuccess: (data) => {
      queryClient.setQueryData(forkprobeKeys.comparison(data.comparisonId), data)
    },
  })
}

// --- History ---

/**
 * List the current user's persisted comparison runs, newest first.
 */
export function useForkprobeHistory(limit = 20, enabled = true) {
  return useQuery({
    queryKey: forkprobeKeys.history(),
    queryFn: () => getComparisonHistory(limit),
    enabled,
    staleTime: 30 * 1000,
  })
}

/**
 * Fetch the full results of a persisted comparison run. Pass `null` when no
 * history entry is selected.
 */
export function useForkprobeHistoryDetail(comparisonId: string | null) {
  return useQuery<ComparisonStatusResponse>({
    queryKey: forkprobeKeys.historyDetail(comparisonId!),
    queryFn: () => getComparisonHistoryDetail(comparisonId!),
    enabled: !!comparisonId,
  })
}

// --- Pipeline run ---

/**
 * Mutation to start a new pipeline (编排) run — a fixed linear chain of skills.
 */
export function useStartPipeline() {
  const queryClient = useQueryClient()

  return useMutation<
    PipelineResponse,
    Error,
    { taskDescription: string; lanes: string[][]; provider?: string }
  >({
    mutationFn: ({ taskDescription, lanes, provider }) =>
      startPipeline(taskDescription, lanes, provider),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: forkprobeKeys.config })
    },
  })
}

/**
 * Mutation to start an autopilot (AI-orchestrated) pipeline run — the user nominates
 * a pool of candidate skills and the LLM orchestrator picks the subset + order.
 */
export function useStartAutopilotPipeline() {
  const queryClient = useQueryClient()

  return useMutation<
    PipelineResponse,
    Error,
    { taskDescription: string; skillCoordinates: string[]; provider?: string }
  >({
    mutationFn: ({ taskDescription, skillCoordinates, provider }) =>
      startAutopilotPipeline(taskDescription, skillCoordinates, provider),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: forkprobeKeys.config })
    },
  })
}

/**
 * Poll pipeline status until a terminal state (COMPLETED / FAILED / CANCELLED).
 * Pass `null` for pipelineId when no pipeline is running.
 */
export function usePipelineStatus(pipelineId: string | null) {
  return useQuery<PipelineStatusResponse>({
    queryKey: forkprobeKeys.pipeline(pipelineId!),
    queryFn: () => getPipelineStatus(pipelineId!),
    enabled: !!pipelineId,
    refetchInterval: (query) => {
      const data = query.state.data
      if (
        data?.status === 'COMPLETED' ||
        data?.status === 'FAILED' ||
        data?.status === 'CANCELLED'
      ) {
        return false
      }
      return 2000 // poll every 2 seconds
    },
  })
}

/**
 * Cancel an in-flight pipeline run.
 */
export function useCancelPipeline() {
  const queryClient = useQueryClient()

  return useMutation<PipelineStatusResponse, Error, string>({
    mutationFn: (pipelineId) => cancelPipeline(pipelineId),
    onSuccess: (data) => {
      queryClient.setQueryData(forkprobeKeys.pipeline(data.pipelineId), data)
    },
  })
}

// --- Pipeline history ---

/**
 * List the current user's persisted pipeline runs, newest first.
 */
export function usePipelineHistory(limit = 20, enabled = true) {
  return useQuery({
    queryKey: forkprobeKeys.pipelineHistory(),
    queryFn: () => getPipelineHistory(limit),
    enabled,
    staleTime: 30 * 1000,
  })
}

/**
 * Fetch the full results of a persisted pipeline run. Pass `null` when no
 * history entry is selected.
 */
export function usePipelineHistoryDetail(pipelineId: string | null) {
  return useQuery<PipelineStatusResponse>({
    queryKey: forkprobeKeys.pipelineHistoryDetail(pipelineId!),
    queryFn: () => getPipelineHistoryDetail(pipelineId!),
    enabled: !!pipelineId,
  })
}
