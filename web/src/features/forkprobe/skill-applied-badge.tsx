import { useTranslation } from 'react-i18next'
import { CheckCircle, AlertTriangle } from 'lucide-react'
import { cn } from '@/shared/lib/utils'

interface SkillAppliedBadgeProps {
  /** true = skill methodology applied, false = not applied, null = not verified */
  skillApplied: boolean | null
  className?: string
}

/**
 * Shared status badge for a verified skill result, used by both the workbench
 * and the comparison-history cards so the wording and icons stay identical.
 *
 * - true  → green "技能已应用"   + check mark
 * - false → amber "技能未应用"   + warning triangle
 * - null  → nothing (not verified yet)
 */
export function SkillAppliedBadge({ skillApplied, className }: SkillAppliedBadgeProps) {
  const { t } = useTranslation()

  if (skillApplied === null) return null

  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full text-xs font-medium',
        skillApplied ? 'bg-emerald-100 text-emerald-700' : 'bg-amber-100 text-amber-700',
        className,
      )}
    >
      {skillApplied ? (
        <>
          {t('forkprobe.skillApplied')}
          <CheckCircle className="w-3.5 h-3.5 text-emerald-600 shrink-0" />
        </>
      ) : (
        <>
          {t('forkprobe.skillNotApplied')}
          <AlertTriangle className="w-3.5 h-3.5 text-amber-500 shrink-0" />
        </>
      )}
    </span>
  )
}
