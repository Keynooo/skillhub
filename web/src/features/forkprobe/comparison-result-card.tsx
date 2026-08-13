import { useTranslation } from 'react-i18next'
import { CheckCircle, AlertTriangle, Clock, Loader2, XCircle } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import type { CandidateResult } from './forkprobe-api'

interface ComparisonResultCardProps {
  result: CandidateResult
  index: number
}

/**
 * Displays a single skill comparison result with visual status indicator.
 *
 * Three visual states:
 * - running:   spinner — skill is still executing
 * - completed: ✅ green (skill applied) or ⚠️ yellow (skill not applied)
 * - error:     red border with error message
 */
export function ComparisonResultCard({ result, index }: ComparisonResultCardProps) {
  const { t } = useTranslation()

  const isRunning = result.output === null && result.error === null
  const isError = result.error !== null
  const isCompleted = !isRunning && !isError
  const skillApplied = result.skillApplied

  const animationDelay = `${index * 80}ms`

  return (
    <div
      className={cn(
        'rounded-xl border p-4 transition-all duration-300',
        isError && 'border-red-400/60 bg-red-50/40',
        isCompleted && skillApplied === true && 'border-emerald-400/60 bg-emerald-50/30',
        isCompleted && skillApplied === false && 'border-amber-400/60 bg-amber-50/30',
        isRunning && 'border-border bg-card',
      )}
      style={{
        animation: `forkprobe-fade-in-up 0.35s ease-out both`,
        animationDelay,
      }}
    >
      {/* Header */}
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2 min-w-0">
          {isRunning && (
            <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
          )}
          {isError && (
            <XCircle className="w-4 h-4 text-red-500 shrink-0" />
          )}
          {isCompleted && skillApplied === true && (
            <CheckCircle className="w-4 h-4 text-emerald-600 shrink-0" />
          )}
          {isCompleted && skillApplied === false && (
            <AlertTriangle className="w-4 h-4 text-amber-500 shrink-0" />
          )}
          <span className="font-semibold text-sm truncate" style={{ color: 'hsl(var(--foreground))' }}>
            {result.skillName}
          </span>
        </div>

        <div className="flex items-center gap-3 text-xs shrink-0" style={{ color: 'hsl(var(--muted-foreground))' }}>
          {isRunning && (
            <div className="flex items-center gap-1">
              <Clock className="w-3 h-3" />
              <span>{result.latencySeconds > 0 ? `${result.latencySeconds.toFixed(1)}s` : '...'}</span>
            </div>
          )}
          {isCompleted && (
            <>
              <span>⏱ {result.latencySeconds.toFixed(1)}{t('forkprobe.latencyUnit')}</span>
              <span>🎫 {result.tokensUsed.toLocaleString()}</span>
            </>
          )}
        </div>
      </div>

      {/* Status badge */}
      {isCompleted && (
        <div
          className={cn(
            'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium mb-3',
            skillApplied === true && 'bg-emerald-100 text-emerald-700',
            skillApplied === false && 'bg-amber-100 text-amber-700',
          )}
        >
          {skillApplied === true ? (
            <>✅ {t('forkprobe.skillApplied')}</>
          ) : (
            <>⚠️ {t('forkprobe.skillNotApplied')}</>
          )}
        </div>
      )}
      {isRunning && (
        <div className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium mb-3 bg-secondary text-muted-foreground">
          <Loader2 className="w-3 h-3 animate-spin" />
          {t('forkprobe.running')}
        </div>
      )}

      {/* Output / Error */}
      {isError && (
        <div className="text-sm text-red-600 bg-red-100/50 rounded-lg p-3">
          {result.error}
        </div>
      )}
      {isCompleted && result.output && (
        <div
          className="text-sm leading-relaxed whitespace-pre-wrap max-h-[300px] overflow-y-auto rounded-lg p-3"
          style={{ background: 'hsl(var(--secondary))', color: 'hsl(var(--foreground))' }}
        >
          {result.output}
        </div>
      )}

      {/* Verification reason */}
      {isCompleted && result.appliedReason && (
        <p className="text-xs mt-2" style={{ color: 'hsl(var(--muted-foreground))' }}>
          {skillApplied === true ? '✅' : '⚠️'} {result.appliedReason}
        </p>
      )}
    </div>
  )
}
