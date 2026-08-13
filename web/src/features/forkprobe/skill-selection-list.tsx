import { useState, useMemo } from 'react'
import { useTranslation } from 'react-i18next'
import { Search, Star } from 'lucide-react'
import { Input } from '@/shared/ui/input'
import { cn } from '@/shared/lib/utils'
import type { RecommendedSkill } from './forkprobe-api'

interface SkillSelectionListProps {
  /** Forkprobe-recommended skills */
  recommendations: RecommendedSkill[]
  /** Flat list of all available skills from SkillHub (for manual browsing) */
  allSkills: RecommendedSkill[]
  /** Currently selected skill coordinates */
  selected: Set<string>
  onToggle: (coordinate: string) => void
  maxSelect: number
}

/**
 * Skill selection interface with three sections:
 * 1. Recommended skills (from catalog match)
 * 2. Baseline reference
 * 3. All skills (searchable)
 *
 * Automatically disables unselected items when the limit is reached.
 */
export function SkillSelectionList({
  recommendations,
  allSkills,
  selected,
  onToggle,
  maxSelect,
}: SkillSelectionListProps) {
  const { t } = useTranslation()
  const [searchQuery, setSearchQuery] = useState('')
  const atLimit = selected.size >= maxSelect

  const filteredAll = useMemo(() => {
    if (!searchQuery.trim()) return allSkills
    const q = searchQuery.toLowerCase()
    return allSkills.filter(
      (s) =>
        s.name.toLowerCase().includes(q) ||
        s.coordinate.toLowerCase().includes(q) ||
        s.reasonZh.toLowerCase().includes(q),
    )
  }, [allSkills, searchQuery])

  return (
    <div className="space-y-4">
      {/* Section 1: Recommended */}
      {recommendations.length > 0 && (
        <section>
          <h4
            className="text-xs font-semibold uppercase tracking-wider mb-2 flex items-center gap-1"
            style={{ color: 'hsl(var(--muted-foreground))' }}
          >
            <Star className="w-3.5 h-3.5 text-amber-500" />
            {t('forkprobe.recommendations')}
          </h4>
          <div className="space-y-1 max-h-[240px] overflow-y-auto">
            {recommendations.map((skill) => (
              <SkillCheckbox
                key={skill.coordinate}
                skill={skill}
                checked={selected.has(skill.coordinate)}
                disabled={atLimit && !selected.has(skill.coordinate)}
                onToggle={() => onToggle(skill.coordinate)}
                showReason
              />
            ))}
          </div>
        </section>
      )}

      {/* Section 2: Baseline (always present as first recommended item) */}
      {/* Baseline is included in recommendations, no separate section needed */}

      {/* Section 3: All Skills */}
      <section>
        <h4
          className="text-xs font-semibold uppercase tracking-wider mb-2"
          style={{ color: 'hsl(var(--muted-foreground))' }}
        >
          {t('forkprobe.allSkills')}
        </h4>

        {/* Search input */}
        <div className="relative mb-2">
          <Search
            className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
            style={{ color: 'hsl(var(--muted-foreground))' }}
          />
          <Input
            className="pl-9 h-9 text-sm"
            placeholder={t('forkprobe.searchSkills')}
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
          />
        </div>

        <div className="space-y-1 max-h-[200px] overflow-y-auto">
          {filteredAll.length === 0 ? (
            <p
              className="text-sm py-3 text-center"
              style={{ color: 'hsl(var(--muted-foreground))' }}
            >
              {searchQuery.trim()
                ? '无匹配的技能'
                : '暂无可用技能'}
            </p>
          ) : (
            filteredAll.map((skill) => (
              <SkillCheckbox
                key={skill.coordinate}
                skill={skill}
                checked={selected.has(skill.coordinate)}
                disabled={atLimit && !selected.has(skill.coordinate)}
                onToggle={() => onToggle(skill.coordinate)}
              />
            ))
          )}
        </div>
      </section>
    </div>
  )
}

/**
 * Single skill checkbox row.
 */
function SkillCheckbox({
  skill,
  checked,
  disabled,
  onToggle,
  showReason = false,
}: {
  skill: RecommendedSkill
  checked: boolean
  disabled: boolean
  onToggle: () => void
  showReason?: boolean
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      disabled={disabled}
      className={cn(
        'w-full flex items-start gap-3 px-3 py-2.5 rounded-lg text-left transition-all duration-150',
        checked
          ? 'bg-brand-gradient/10 border border-primary/30'
          : 'border border-transparent hover:bg-secondary/80',
        disabled && !checked && 'opacity-40 cursor-not-allowed',
      )}
    >
      {/* Custom checkbox */}
      <div
        className={cn(
          'w-5 h-5 rounded-md border-2 flex items-center justify-center shrink-0 mt-0.5 transition-colors duration-150',
          checked
            ? 'border-primary bg-primary text-white'
            : 'border-border bg-white',
        )}
      >
        {checked && (
          <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
            <path d="M13.3 3.3L6 10.6 2.7 7.3 1.3 8.7l4 4c.4.4 1 .4 1.4 0l8-8-1.4-1.4z" />
          </svg>
        )}
      </div>

      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2">
          <span
            className={cn(
              'text-sm font-medium',
              checked ? 'text-primary' : '',
            )}
            style={!checked ? { color: 'hsl(var(--foreground))' } : undefined}
          >
            {skill.name}
          </span>
          {skill.source === 'baseline' && (
            <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-secondary text-muted-foreground font-medium">
              {skill.name.includes('Baseline') ? 'BASELINE' : skill.source}
            </span>
          )}
          {skill.source === 'catalog' && (
            <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-violet-100 text-violet-700 font-medium">
              CATALOG
            </span>
          )}
        </div>
        {showReason && skill.reasonZh && (
          <p
            className="text-xs mt-0.5 line-clamp-2"
            style={{ color: 'hsl(var(--muted-foreground))' }}
          >
            {skill.reasonZh}
          </p>
        )}
      </div>
    </button>
  )
}
