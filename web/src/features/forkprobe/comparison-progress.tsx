import { useTranslation } from 'react-i18next'
import { Loader2, CheckCircle2 } from 'lucide-react'
import type { CandidateResult } from './forkprobe-api'

interface ComparisonProgressProps {
  results: CandidateResult[]
}

/**
 * Running-state progress display — shows one row per skill with spinner
 * or checkmark, plus overall progress.
 */
export function ComparisonProgress({ results }: ComparisonProgressProps) {
  const { t } = useTranslation()

  const completedCount = results.filter(
    (r) => r.output !== null || r.error !== null,
  ).length
  const total = results.length
  const percent = total > 0 ? Math.round((completedCount / total) * 100) : 0

  return (
    <div className="space-y-4">
      {/* Overall progress bar */}
      <div>
        <div className="flex items-center justify-between mb-2 text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
          <span>{t('forkprobe.running')}</span>
          <span>{completedCount}/{total}</span>
        </div>
        <div className="w-full h-2 rounded-full overflow-hidden bg-secondary">
          <div
            className="h-full rounded-full transition-all duration-500 ease-out"
            style={{
              width: `${percent}%`,
              background: 'var(--brand-gradient)',
            }}
          />
        </div>
      </div>

      {/* Per-skill rows */}
      <div className="space-y-3">
        {results.map((result) => {
          const isDone = result.output !== null || result.error !== null
          return (
            <div
              key={result.skillCoordinate}
              className="flex items-center justify-between py-2 px-3 rounded-lg"
              style={{ background: 'hsl(var(--secondary))' }}
            >
              <div className="flex items-center gap-2">
                {isDone ? (
                  result.error ? (
                    <span className="text-red-500 text-sm">❌</span>
                  ) : (
                    <CheckCircle2 className="w-4 h-4 text-emerald-500" />
                  )
                ) : (
                  <Loader2 className="w-4 h-4 text-primary animate-spin" />
                )}
                <span
                  className="text-sm font-medium"
                  style={{ color: 'hsl(var(--foreground))' }}
                >
                  {result.skillName}
                </span>
              </div>
              <span
                className="text-xs"
                style={{ color: 'hsl(var(--muted-foreground))' }}
              >
                {isDone
                  ? `${result.latencySeconds.toFixed(1)}${t('forkprobe.latencyUnit')}`
                  : result.latencySeconds > 0
                    ? `${result.latencySeconds.toFixed(1)}${t('forkprobe.latencyUnit')}`
                    : '...'}
              </span>
            </div>
          )
        })}
      </div>
    </div>
  )
}
