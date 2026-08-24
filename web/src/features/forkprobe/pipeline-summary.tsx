import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Layers, CheckCircle2, XCircle, CircleDashed, AlertTriangle } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import { ForkprobeOutput } from './forkprobe-output'
import { ForkprobeFileList } from './forkprobe-files'
import type { PipelineLaneResult, PipelineStageResult } from './forkprobe-api'

interface PipelineSummaryProps {
  lanes: PipelineLaneResult[]
}

const isBaselineStage = (stage: PipelineStageResult) => stage.skillCoordinate === 'baseline'
const isBaselineLane = (lane: PipelineLaneResult) =>
  lane.stages.length === 1 && isBaselineStage(lane.stages[0])

function laneLabel(lane: PipelineLaneResult, t: (k: string) => string) {
  const first = lane.stages.find((s) => !isBaselineStage(s))
  if (!first) return t('forkprobe.nativeLane')
  return lane.stages.length > 1 ? `${first.skillName} +${lane.stages.length - 1}` : first.skillName
}

function laneStatusIcon(lane: PipelineLaneResult) {
  if (lane.stages.some((s) => s.status === 'FAILED'))
    return <XCircle className="w-3 h-3 text-red-500 shrink-0" />
  // Baseline/native lane has no "skill applied" concept — a clean finish is a ✓.
  if (isBaselineLane(lane))
    return <CheckCircle2 className="w-3 h-3 text-emerald-500 shrink-0" />
  const applied = lane.stages.filter((s) => s.skillApplied === true).length
  if (applied > 0)
    return <CheckCircle2 className="w-3 h-3 text-emerald-500 shrink-0" />
  if (lane.stages.some((s) => s.status === 'SKIPPED'))
    return <CircleDashed className="w-3 h-3 text-muted-foreground shrink-0" />
  return <CircleDashed className="w-3 h-3 text-muted-foreground/50 shrink-0" />
}

/**
 * Completed-state summary: the same browser-style tab strip, one tab per lane.
 * The active tab's panel shows that lane's per-stage table and final output —
 * full width, so long outputs read comfortably instead of in a 3-column crush.
 */
export function PipelineSummary({ lanes }: PipelineSummaryProps) {
  const { t } = useTranslation()
  const [activeLane, setActiveLane] = useState(
    // Default to the first non-baseline lane (the interesting result).
    Math.max(0, lanes.findIndex((l) => !isBaselineLane(l))),
  )
  const active = Math.min(activeLane, lanes.length - 1)
  const lane = lanes[active]

  const appliedStages = lanes.flatMap((l) => l.stages).filter((s) => !isBaselineStage(s))
  const appliedCount = appliedStages.filter((s) => s.skillApplied === true).length
  const total = appliedStages.length

  return (
    <div className="space-y-4">
      {/* Applied-count header (excludes baseline reference lanes) */}
      {total > 0 && (
        <div className="flex items-center gap-2">
          <Layers className="w-5 h-5 text-primary shrink-0" />
          <span className="text-base font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
            {t('forkprobe.appliedCount', { applied: appliedCount, total })}
          </span>
        </div>
      )}

      {/* Lane tabs */}
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
              {laneStatusIcon(l)}
              <span className="truncate" title={laneLabel(l, t)}>
                {laneLabel(l, t)}
              </span>
            </button>
          )
        })}
      </div>

      {/* Active lane panel — full width for reading */}
      {lane && (
        <LanePanel key={lane.index} lane={lane} />
      )}
    </div>
  )
}

function LanePanel({ lane }: { lane: PipelineLaneResult }) {
  const { t } = useTranslation()
  const lastCompleted =
    [...lane.stages].reverse().find((s) => s.status === 'COMPLETED' && s.output) ?? null
  const baseline = isBaselineLane(lane)

  return (
    <div
      className="space-y-3 rounded-xl border rounded-tl-none p-4"
      style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
    >
      <div className="flex items-center justify-between">
        <div className="text-sm font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
          {t('forkprobe.lane', { n: lane.index + 1 })}
        </div>
        {baseline && (
          <span
            className="text-[10px] px-1.5 py-0.5 rounded-full font-medium"
            style={{ color: 'hsl(var(--muted-foreground))', background: 'hsl(var(--muted))' }}
          >
            {t('forkprobe.nativeLane')}
          </span>
        )}
      </div>

      {/* Final output of the lane (last completed stage) — the headline. */}
      {lastCompleted && (
        <div className="space-y-2">
          <div className="flex items-center justify-between gap-3">
            <h4 className="text-xs font-semibold shrink-0" style={{ color: 'hsl(var(--foreground))' }}>
              {baseline ? t('forkprobe.nativeOutput') : t('forkprobe.combinedOutput')}
            </h4>
            {lane.stages.filter((s) => !isBaselineStage(s)).length > 0 && (
              <div className="flex items-center gap-1.5 min-w-0 flex-wrap justify-end">
                {lane.stages
                  .filter((s) => !isBaselineStage(s))
                  .map((s) => (
                    <span
                      key={`${s.skillCoordinate}-${s.index}`}
                      title={
                        s.appliedReason
                          ? `${s.skillApplied === true ? '✅' : s.skillApplied === false ? '⚠️' : '·'} ${s.appliedReason}`
                          : s.skillName
                      }
                      className={cn(
                        'inline-flex items-center gap-1 text-[11px] px-2 py-0.5 rounded-full font-medium truncate max-w-[180px]',
                      )}
                      style={
                        s.skillApplied === true
                          ? { color: '#15803d', background: 'rgba(34,197,94,0.10)' }
                          : s.skillApplied === false
                            ? { color: '#b45309', background: 'rgba(245,158,11,0.12)' }
                            : { color: 'hsl(var(--muted-foreground))', background: 'hsl(var(--muted))' }
                      }
                    >
                      {s.skillApplied === true && <CheckCircle2 className="w-3 h-3 shrink-0" />}
                      {s.skillApplied === false && <AlertTriangle className="w-3 h-3 shrink-0" />}
                      <span className="truncate">{s.skillName}</span>
                    </span>
                  ))}
              </div>
            )}
          </div>
          <div
            className="rounded-lg border p-4 max-h-[520px] overflow-y-auto"
            style={{
              background: 'hsl(var(--background))',
              borderColor: 'hsl(var(--border))',
            }}
          >
            <ForkprobeOutput content={lastCompleted.output!} className="max-w-[76ch]" />
          </div>
          {lastCompleted.files && lastCompleted.files.length > 0 && (
            <ForkprobeFileList files={lastCompleted.files} />
          )}
        </div>
      )}
    </div>
  )
}
