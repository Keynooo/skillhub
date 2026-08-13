import { useEffect, useState } from 'react'
import { Search, Loader2, Plus } from 'lucide-react'
import { Input } from '@/shared/ui/input'
import { cn } from '@/shared/lib/utils'
import { useSearchSkills } from '@/shared/hooks/use-skill-queries'
import type { RecommendedSkill } from './forkprobe-api'

interface SkillSearchBoxProps {
  selected: Set<string>
  onAdd: (skill: RecommendedSkill) => void
  maxSelect: number
}

/**
 * Debounced search box that queries the real SkillHub skill registry
 * (GET /api/web/skills) and lets the user add a matched skill directly
 * into the comparison candidate list, bypassing catalog recommendation.
 */
export function SkillSearchBox({ selected, onAdd, maxSelect }: SkillSearchBoxProps) {
  const [query, setQuery] = useState('')
  const [debounced, setDebounced] = useState('')

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(query.trim()), 300)
    return () => clearTimeout(timer)
  }, [query])

  const enabled = debounced.length >= 2
  const { data, isFetching } = useSearchSkills(
    { q: debounced, sort: 'relevance', page: 0, size: 8 },
    enabled,
  )

  const results = data?.items ?? []
  const atLimit = selected.size >= maxSelect

  return (
    <div className="space-y-2">
      <div className="relative">
        <Search
          className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4"
          style={{ color: 'hsl(var(--muted-foreground))' }}
        />
        <Input
          className="pl-9 h-9 text-sm"
          placeholder="搜索平台 skill，直接加入对比…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {isFetching && (
          <Loader2 className="absolute right-3 top-1/2 -translate-y-1/2 w-4 h-4 animate-spin text-muted-foreground" />
        )}
      </div>

      {enabled && !isFetching && results.length === 0 && (
        <p className="text-xs px-1 py-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
          无匹配 skill
        </p>
      )}

      {enabled && results.length > 0 && (
        <div className="space-y-0.5 max-h-[220px] overflow-y-auto rounded-lg border p-1"
          style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
        >
          {results.map((s) => {
            const coordinate = `${s.namespace}/${s.slug}`
            const isAdded = selected.has(coordinate)
            const disabled = atLimit && !isAdded
            return (
              <button
                key={coordinate}
                type="button"
                disabled={disabled}
                onClick={() =>
                  onAdd({
                    coordinate,
                    name: s.displayName,
                    namespace: s.namespace,
                    reasonZh: s.summary ?? '',
                    domain: 'skillhub',
                    source: 'skillhub',
                    stars: s.starCount ?? 0,
                  })
                }
                className={cn(
                  'w-full flex items-start gap-2 px-2.5 py-2 rounded-md text-left transition-colors',
                  isAdded ? 'bg-primary/5' : 'hover:bg-muted/50',
                  disabled && 'opacity-40 cursor-not-allowed',
                )}
              >
                <Plus className="w-4 h-4 shrink-0 mt-0.5 text-muted-foreground" />
                <div className="flex-1 min-w-0">
                  <div className="text-sm font-medium truncate" style={{ color: 'hsl(var(--foreground))' }}>
                    {s.displayName}
                  </div>
                  <div className="text-xs font-mono truncate" style={{ color: 'hsl(var(--muted-foreground))' }}>
                    {coordinate}
                  </div>
                </div>
              </button>
            )
          })}
        </div>
      )}
    </div>
  )
}
