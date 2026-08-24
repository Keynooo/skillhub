import { useEffect, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { useNavigate } from '@tanstack/react-router'
import { X, ExternalLink, Plus, Search, Sparkles, Info, Check, ChevronRight } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import { Input } from '@/shared/ui/input'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import { resolveRecommendedSkillLink } from './forkprobe-api'
import type { RecommendedSkill } from './forkprobe-api'

interface PipelineStagePickerProps {
  /** 1..MAX_LANES lanes; each lane is an unordered pool of skill coordinates (0..5). */
  lanes: string[][]
  /** Flat list of already-known skills (recommendations + manually added). */
  allSkills: RecommendedSkill[]
  onAdd: (laneIndex: number, skill: RecommendedSkill) => void
  onRemove: (laneIndex: number, stageIndex: number) => void
  onAddLane: () => void
  onRemoveLane: (laneIndex: number) => void
  maxStages: number
  maxLanes: number
}

function fallbackSkill(coordinate: string): RecommendedSkill {
  const parts = coordinate.split('/')
  const slug = parts.length === 2 ? parts[1] : coordinate
  const namespace = parts.length === 2 ? parts[0] : ''
  return {
    coordinate,
    name: slug,
    namespace,
    reasonZh: '',
    domain: 'skillhub',
    source: 'skillhub',
    stars: 0,
    sourceUrl: null,
  }
}

/** Lane summary shown under the tab number: first skill names, or "原生". */
function laneSubtitle(
  lane: string[],
  skillMap: Map<string, RecommendedSkill>,
  nativeLabel: string,
): string {
  if (lane.length === 0) return nativeLabel
  return lane
    .slice(0, 2)
    .map((c) => (skillMap.get(c) ?? fallbackSkill(c)).name)
    .join(' · ') + (lane.length > 2 ? ` +${lane.length - 2}` : '')
}

/**
 * Browser-style tabbed lane selector with a click-based skill picker. Each tab
 * is one lane — the top + opens a new lane, hover-× closes one (min 1). The
 * active tab's panel shows that lane's skills; the left column lists skills
 * (recommended first, then live search results) each with a + button to add it
 * to the active lane. An empty lane runs as the native/baseline reference.
 */
export function PipelineStagePicker({
  lanes,
  allSkills,
  onAdd,
  onRemove,
  onAddLane,
  onRemoveLane,
  maxStages,
  maxLanes,
}: PipelineStagePickerProps) {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const [activeLane, setActiveLane] = useState(0)
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(query.trim()), 300)
    return () => clearTimeout(timer)
  }, [query])

  const enabled = debounced.length >= 2
  const { data, isFetching } = useSearchSkills(
    { q: debounced, sort: 'relevance', page: 0, size: 10 },
    enabled,
  )

  const skillMap = useMemo(() => {
    const map = new Map<string, RecommendedSkill>()
    for (const s of allSkills) map.set(s.coordinate, s)
    return map
  }, [allSkills])

  // Keep the active tab valid after a lane is closed.
  const active = Math.min(activeLane, lanes.length - 1)
  const lane = lanes[active] ?? []
  const empty = lane.length === 0
  const full = lane.length >= maxStages

  /** Skills to list in the left column: search results, else known skills. */
  const listed: RecommendedSkill[] = enabled
    ? (data?.items ?? []).map((s) => ({
        coordinate: `${s.namespace}/${s.slug}`,
        name: s.displayName,
        namespace: s.namespace,
        reasonZh: s.summary ?? '',
        domain: 'skillhub',
        source: 'skillhub',
        stars: s.starCount ?? 0,
        sourceUrl: null,
      }))
    : allSkills

  return (
    <div className="space-y-3">
      {/* ─── Tab strip ─── */}
      <div
        role="tablist"
        aria-label={t('forkprobe.lanesTablist')}
        className="flex items-end gap-1.5 border-b"
        style={{ borderColor: 'hsl(var(--border))' }}
      >
        {lanes.map((l, i) => {
          const isActive = i === active
          return (
            <div
              key={i}
              role="tab"
              aria-selected={isActive}
              tabIndex={0}
              onClick={() => setActiveLane(i)}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault()
                  setActiveLane(i)
                }
              }}
              className={cn(
                'group relative flex flex-col items-start gap-0.5 min-w-[120px] max-w-[220px] px-4 pt-2.5 pb-2 -mb-px cursor-pointer select-none transition-colors rounded-t-xl border-x border-t',
                isActive
                  ? 'font-medium text-primary'
                  : 'text-muted-foreground hover:text-foreground/80 hover:bg-muted/40',
              )}
              style={{
                borderColor: isActive ? 'hsl(var(--border))' : 'transparent',
                background: isActive ? 'hsl(var(--card))' : 'transparent',
              }}
            >
              <span className="flex items-center gap-2 w-full">
                <span className="text-sm leading-none">
                  {t('forkprobe.lane', { n: i + 1 })}
                </span>
                {l.length === 0 && (
                  <span
                    className="text-[10px] px-1.5 py-0.5 rounded-full font-medium leading-none"
                    style={{
                      color: 'hsl(var(--muted-foreground))',
                      background: 'hsl(var(--muted))',
                    }}
                  >
                    {t('forkprobe.nativeLane')}
                  </span>
                )}
                {lanes.length > 1 && (
                  <button
                    type="button"
                    aria-label={t('forkprobe.closeLane')}
                    title={t('forkprobe.closeLane')}
                    onClick={(e) => {
                      e.stopPropagation()
                      onRemoveLane(i)
                      // Keep a sensible tab selected after closing this one.
                      if (i < active) setActiveLane(active - 1)
                      else if (i === active) setActiveLane(Math.max(0, i - 1))
                    }}
                    className="ml-auto shrink-0 p-0.5 rounded-md opacity-0 group-hover:opacity-100 focus-visible:opacity-100 hover:bg-muted transition-opacity"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                )}
              </span>
              <span
                className="text-[11px] leading-none max-w-full truncate"
                style={{ color: 'hsl(var(--muted-foreground))' }}
                title={laneSubtitle(l, skillMap, t('forkprobe.nativeLane'))}
              >
                {laneSubtitle(l, skillMap, t('forkprobe.nativeLane'))}
              </span>
            </div>
          )
        })}

        {/* + new lane tab */}
        {lanes.length < maxLanes && (
          <button
            type="button"
            onClick={() => {
              onAddLane()
              setActiveLane(lanes.length)
            }}
            title={t('forkprobe.addLane')}
            className="inline-flex flex-col items-center justify-center gap-0.5 px-4 pt-2.5 pb-2 -mb-px text-sm text-muted-foreground hover:text-foreground rounded-t-xl hover:bg-muted/40 transition-colors shrink-0 border-x border-t"
            style={{ borderColor: 'transparent' }}
          >
            <Plus className="w-4 h-4" aria-hidden />
            <span className="text-[11px] leading-none">{t('forkprobe.addLane')}</span>
          </button>
        )}
      </div>

      {/* ─── Two columns: lane panel (right, wide) + skill list (left) ─── */}
      <div className="grid grid-cols-1 md:grid-cols-[minmax(0,340px)_minmax(0,1fr)] gap-4">
        {/* Skill list */}
        <div
          className="rounded-xl border p-3 space-y-2 max-h-[480px] overflow-y-auto"
          style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
        >
          <div className="relative sticky -top-3 -mx-3 px-3 pt-1 pb-2 z-[1]" style={{ background: 'hsl(var(--card))' }}>
            <Search
              className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 mt-1"
              style={{ color: 'hsl(var(--muted-foreground))' }}
            />
            <Input
              className="pl-9 h-9 text-sm"
              placeholder={t('forkprobe.searchPlatformSkillPipeline')}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>

          {isFetching && (
            <p className="text-xs px-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
              {t('forkprobe.searching')}
            </p>
          )}
          {!isFetching && enabled && listed.length === 0 && (
            <p className="text-xs px-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
              {t('forkprobe.noMatchSkill')}
            </p>
          )}
          {!enabled && listed.length === 0 && (
            <p className="text-xs px-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
              {t('forkprobe.noSkillsAvailable')}
            </p>
          )}

          <div className="space-y-1">
            {listed.map((skill) => {
              const added = lane.includes(skill.coordinate)
              const disabled = full && !added
              const link = resolveRecommendedSkillLink(skill)
              return (
                <div
                  key={skill.coordinate}
                  role="button"
                  tabIndex={disabled ? -1 : 0}
                  aria-pressed={added}
                  onClick={() => !disabled && onAdd(active, skill)}
                  onKeyDown={(e) => {
                    if (disabled) return
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault()
                      onAdd(active, skill)
                    }
                  }}
                  title={
                    disabled
                      ? t('forkprobe.maxStagesHint', { n: maxStages })
                      : added
                        ? t('forkprobe.removeFromLane')
                        : t('forkprobe.addStage')
                  }
                  className={cn(
                    'flex items-start gap-2 px-2.5 py-2 rounded-lg transition-colors select-none',
                    added ? 'bg-primary/5' : 'hover:bg-muted/50 cursor-pointer',
                    disabled && 'opacity-40 cursor-not-allowed',
                  )}
                >
                  <span
                    className={cn(
                      'shrink-0 mt-0.5 p-1 rounded-md transition-colors',
                      added
                        ? 'bg-primary/10 text-primary'
                        : 'text-muted-foreground',
                    )}
                  >
                    {added ? <Check className="w-4 h-4" /> : <Plus className="w-4 h-4" />}
                  </span>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-1">
                      <span
                        className="text-sm font-medium truncate"
                        style={{ color: 'hsl(var(--foreground))' }}
                      >
                        {skill.name}
                      </span>
                      {link?.kind === 'detail' ? (
                        <button
                          type="button"
                          onClick={(e) => {
                            e.stopPropagation()
                            navigate({
                              to: '/space/$namespace/$slug',
                              params: { namespace: link.namespace, slug: link.slug },
                              search: { returnTo: '/forkprobe' },
                            })
                          }}
                          title={t('forkprobe.viewDetail')}
                          aria-label={t('forkprobe.viewDetail')}
                          className="shrink-0 p-1 rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                        >
                          <Info className="w-3.5 h-3.5" />
                        </button>
                      ) : link?.kind === 'external' ? (
                        <a
                          href={link.href}
                          target="_blank"
                          rel="noopener noreferrer"
                          onClick={(e) => e.stopPropagation()}
                          title={t('forkprobe.viewSource')}
                          className="shrink-0 p-1 rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                        >
                          <ExternalLink className="w-3.5 h-3.5" />
                        </a>
                      ) : null}
                    </div>
                    {skill.reasonZh && (
                      <p
                        className="text-xs line-clamp-2 mt-0.5"
                        style={{ color: 'hsl(var(--muted-foreground))' }}
                        title={skill.reasonZh}
                      >
                        {skill.reasonZh}
                      </p>
                    )}
                  </div>
                </div>
              )
            })}
          </div>
        </div>

        {/* Active lane panel */}
        <div
          className="rounded-xl border rounded-tl-none p-4 space-y-3"
          style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
        >
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2.5 min-w-0">
              <span
                className="text-sm font-semibold shrink-0"
                style={{ color: 'hsl(var(--foreground))' }}
              >
                {t('forkprobe.lane', { n: active + 1 })}
              </span>
              {!empty && (
                <span
                  className="text-[11px] px-2 py-0.5 rounded-full font-medium shrink-0 tabular-nums"
                  style={{
                    color: 'hsl(var(--primary))',
                    background: 'hsl(var(--primary) / 0.08)',
                  }}
                  title={t('forkprobe.laneSkillCount', { n: lane.length })}
                >
                  {lane.length}
                </span>
              )}
            </div>
            {full && (
              <span className="text-xs shrink-0" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {t('forkprobe.maxStagesHint', { n: maxStages })}
              </span>
            )}
          </div>

          {empty ? (
            <div
              className="flex flex-col items-center justify-center text-center gap-2 py-10 px-6 rounded-lg"
              style={{ background: 'hsl(var(--muted) / 0.35)' }}
            >
              <Sparkles className="w-6 h-6" style={{ color: 'hsl(var(--primary))' }} aria-hidden />
              <p className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                {t('forkprobe.nativeLaneTitle')}
              </p>
              <p className="text-xs max-w-[36ch] leading-relaxed" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {t('forkprobe.nativeLaneHint')}
              </p>
            </div>
          ) : null}

          {/* Skill pool of the active lane */}
          {lane.length > 0 && (
            <div className="space-y-1.5">
              {lane.map((coordinate, i) => {
                const skill = skillMap.get(coordinate) ?? fallbackSkill(coordinate)
                const link = resolveRecommendedSkillLink(skill)
                return (
                  <div
                    key={coordinate}
                    className="flex items-center gap-1.5 px-2.5 py-2 rounded-lg border"
                    style={{ borderColor: 'hsl(var(--border))', background: 'hsl(var(--background))' }}
                  >
                    <span
                      className="shrink-0 w-6 h-6 rounded-full flex items-center justify-center text-[11px] font-bold text-white tabular-nums"
                      style={{ background: 'var(--brand-gradient)' }}
                    >
                      {i + 1}
                    </span>
                    <div className="flex-1 min-w-0">
                      {link?.kind === 'detail' ? (
                        <button
                          type="button"
                          onClick={() =>
                            navigate({
                              to: '/space/$namespace/$slug',
                              params: { namespace: link.namespace, slug: link.slug },
                              search: { returnTo: '/forkprobe' },
                            })
                          }
                          title={t('forkprobe.viewDetail')}
                          className="block w-full text-left group"
                        >
                          <span
                            className="flex items-center gap-1 text-sm font-medium truncate group-hover:underline"
                            style={{ color: 'hsl(var(--foreground))' }}
                          >
                            {skill.name}
                            <ChevronRight className="w-3.5 h-3.5 shrink-0 text-primary" />
                          </span>
                        </button>
                      ) : (
                        <div
                          className="text-sm font-medium truncate"
                          style={{ color: 'hsl(var(--foreground))' }}
                        >
                          {skill.name}
                        </div>
                      )}
                      <div
                        className="text-[11px] font-mono truncate"
                        style={{ color: 'hsl(var(--muted-foreground))' }}
                      >
                        {coordinate}
                      </div>
                    </div>

                    <div className="flex items-center gap-0.5 shrink-0">
                      <button
                        type="button"
                        onClick={() => onRemove(active, i)}
                        aria-label={t('forkprobe.removeStage')}
                        className="p-1.5 rounded-md text-muted-foreground hover:text-red-500 hover:bg-red-50 transition-colors"
                      >
                        <X className="w-4 h-4" />
                      </button>
                      {link?.kind === 'detail' && (
                        <button
                          type="button"
                          title={t('forkprobe.viewDetail')}
                          onClick={() =>
                            navigate({
                              to: '/space/$namespace/$slug',
                              params: { namespace: link.namespace, slug: link.slug },
                              search: { returnTo: '/forkprobe' },
                            })
                          }
                          className="p-1.5 rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                        >
                          <ExternalLink className="w-4 h-4" />
                        </button>
                      )}
                      {link?.kind === 'external' && (
                        <a
                          href={link.href}
                          target="_blank"
                          rel="noopener noreferrer"
                          title={t('forkprobe.viewSource')}
                          className="p-1.5 rounded-md text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
                        >
                          <ExternalLink className="w-4 h-4" />
                        </a>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>

      <p className="text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
        {t('forkprobe.laneHint', { n: maxLanes })}
      </p>
    </div>
  )
}
