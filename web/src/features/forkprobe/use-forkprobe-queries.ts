import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import {
  recommendSkills,
  startComparison,
  getComparisonStatus,
  cancelComparison,
  getForkprobeConfig,
  type ComparisonStatusResponse,
  type CompareResponse,
} from './forkprobe-api'

// --- Query keys ---

export const forkprobeKeys = {
  config: ['forkprobe', 'config'] as const,
  recommend: (taskDescription: string) =>
    ['forkprobe', 'recommend', taskDescription] as const,
  comparison: (comparisonId: string) =>
    ['forkprobe', 'comparison', comparisonId] as const,
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
