import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Loader2, CheckCircle2, XCircle } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import type { PipelineLaneResult } from './forkprobe-api'

interface PipelineProgressProps {
  lanes: PipelineLaneResult[]
}

const isBaselineStage = (stage: { skillCoordinate: string }) => stage.skillCoordinate === 'baseline'

function laneLabel(lane: PipelineLaneResult, t: (k: string) => string) {
  const first = lane.stages.find((s) => !isBaselineStage(s))
  if (!first) return t('forkprobe.nativeLane')
  return lane.stages.length > 1 ? `${first.skillName} +${lane.stages.length - 1}` : first.skillName
}

/** Status dot for a lane tab: running / done / failed. */
function laneDot(lane: PipelineLaneResult) {
  if (lane.status === 'RUNNING') return <Loader2 className="w-3 h-3 animate-spin text-primary" />
  if (lane.status === 'COMPLETED')
    return <CheckCircle2 className="w-3 h-3 text-emerald-500 shrink-0" />
  if (lane.status === 'FAILED') return <XCircle className="w-3 h-3 text-red-500 shrink-0" />
  return <span className="w-3 h-3 rounded-full bg-muted-foreground/30" />
}

/**
 * Running-state view: the same browser-style tab strip the picker used — one
 * tab per lane with a live status dot. The active tab's panel shows a single
 * calm lane-level status line (the lane is one opaque orchestrated run; the
 * per-stage timeline is intentionally not exposed while running).
 */
export function PipelineProgress({ lanes }: PipelineProgressProps) {
  const { t } = useTranslation()
  const [activeLane, setActiveLane] = useState(0)
  const active = Math.min(activeLane, lanes.length - 1)
  const lane = lanes[active]

  const runningCount = lanes.filter((l) => l.status === 'RUNNING').length
  const doneCount = lanes.filter(
    (l) => l.status === 'COMPLETED' || l.status === 'FAILED',
  ).length

  return (
    <div className="space-y-4">
      {/* Overall progress bar */}
      <div>
        <div
          className="flex items-center justify-between mb-2 text-sm"
          style={{ color: 'hsl(var(--muted-foreground))' }}
        >
          <span>{t('forkprobe.pipelineRunning')}</span>
          <span className="tabular-nums">
            {doneCount}/{lanes.length}
          </span>
        </div>
        <div className="w-full h-2 rounded-full overflow-hidden bg-secondary">
          <div
            className="h-full rounded-full transition-all duration-500 ease-out"
            style={{
              width: `${lanes.length > 0 ? Math.round((doneCount / lanes.length) * 100) : 0}%`,
              background: 'var(--brand-gradient)',
            }}
          />
        </div>
      </div>

      {/* Lane tabs (same strip as the picker) */}
      <div
        role="tablist"
        aria-label={t('forkprobe.lanesTablist')}
        className="flex items-end gap-1 border-b overflow-x-auto overflow-y-hidden"
        style={{ borderColor: 'hsl(var(--border))' }}
      >
        {lanes.map((l, i) => {
          const isActive = i === active
          return (
            <button
              key={l.index}
              type="button"
              role="tab"
              aria-selected={isActive}
              onClick={() => setActiveLane(i)}
              className={cn(
                'inline-flex items-center gap-1.5 max-w-[220px] px-3.5 pt-2 pb-2.5 -mb-px text-sm cursor-pointer select-none transition-colors rounded-t-lg',
                isActive
                  ? 'border-x border-t font-medium text-primary'
                  : 'text-muted-foreground hover:text-foreground/80 hover:bg-muted/40',
              )}
              style={{
                borderColor: isActive ? 'hsl(var(--border))' : 'transparent',
                background: isActive ? 'hsl(var(--card))' : 'transparent',
              }}
            >
              {laneDot(l)}
              <span className="truncate" title={laneLabel(l, t)}>
                {laneLabel(l, t)}
              </span>
            </button>
          )
        })}
      </div>

      {/* Active lane — one opaque running card, no per-stage timeline */}
      {lane && (
        <div
          className="rounded-xl border rounded-tl-none p-8 flex flex-col items-center justify-center text-center min-h-[160px]"
          style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
        >
          {lane.status === 'RUNNING' && (
            <>
              <Loader2 className="w-8 h-8 text-primary animate-spin mb-3" />
              <p className="text-sm font-medium" style={{ color: 'hsl(var(--foreground))' }}>
                {t('forkprobe.laneRunning', { n: lane.index + 1 })}
              </p>
              <p className="text-xs mt-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {t('forkprobe.laneRunningHint')}
              </p>
            </>
          )}
          {lane.status === 'COMPLETED' && (
            <>
              <CheckCircle2 className="w-8 h-8 text-emerald-500 mb-3" />
              <p className="text-sm font-medium" style={{ color: 'hsl(var(--foreground))' }}>
                {t('forkprobe.laneDone', { n: lane.index + 1 })}
              </p>
            </>
          )}
          {lane.status === 'FAILED' && (
            <>
              <XCircle className="w-8 h-8 text-red-500 mb-3" />
              <p className="text-sm font-medium" style={{ color: 'hsl(var(--foreground))' }}>
                {t('forkprobe.stageFailed')}
              </p>
            </>
          )}
          {lane.status === 'PENDING' && runningCount > 0 && (
            <p className="text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
              {t('forkprobe.laneQueued')}
            </p>
          )}
        </div>
      )}
    </div>
  )
}
