import { useState, useMemo, useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import {
  GitCompare,
  Sparkles,
  RefreshCw,
  AlertCircle,
  CheckCircle2,
  Loader2,
  ChevronRight,
  Hash,
  ExternalLink,
  Square,
  History,
} from 'lucide-react'
import { useNavigate } from '@tanstack/react-router'
import { Button } from '@/shared/ui/button'
import { Textarea } from '@/shared/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/select'
import { cn } from '@/shared/lib/utils'
import { useForkprobeWorkbench } from '@/features/forkprobe/use-forkprobe-workbench'
import { SkillSearchBox } from '@/features/forkprobe/skill-search-box'
import type { CandidateResult } from '@/features/forkprobe/forkprobe-api'
import type { RecommendedSkill } from '@/features/forkprobe/forkprobe-api'
import { resolveSkillLink, resolveRecommendedSkillLink } from '@/features/forkprobe/forkprobe-api'
import { ForkprobeOutput } from '@/features/forkprobe/forkprobe-output'
import { ForkprobeFileList } from '@/features/forkprobe/forkprobe-files'
import { ComparisonHistory } from '@/features/forkprobe/comparison-history'
import { SkillAppliedBadge } from '@/features/forkprobe/skill-applied-badge'

/** Friendly display names for the known providers; falls back to id. */
const PROVIDER_LABELS: Record<string, string> = {
  glm: 'GLM',
  deepseek: 'DeepSeek',
}

/**
 * Render a provider option. The dropdown is fixed to the three known providers
 * (DeepSeek / GLM / 本地 vLLM); their availability is still controlled by
 * server config. The model name is always appended when known so the operator
 * can see exactly which concrete model each provider is pointed at (it changes
 * over time, e.g. deepseek-v4-flash today, pro tomorrow).
 */
function providerLabel(id: string, model: string, localLabel: string): string {
  const base = id === 'local' ? localLabel : PROVIDER_LABELS[id] ?? id
  return model ? `${base} · ${model}` : base
}

/**
 * Full-screen forkprobe comparison workbench — styled to match the
 * forkprobe reference design (https://jayden-x-l.github.io/forkprobe/).
 *
 * Layout (COMPLETED state):
 *   Top bar: report title + stats + step indicator
 *   Left:    task summary + candidate skill tab buttons
 *   Center:  selected skill output with metadata
 *   Right:   winner selection + follow-up action (detail page / source)
 */
export function ForkprobeWorkbenchPage() {
  const { t } = useTranslation()

  const preselectedSkills = useMemo(() => {
    const params = new URLSearchParams(window.location.search)
    const preselect = params.get('preselect')
    if (!preselect) return []
    return preselect
      .split(',')
      .map((entry) => entry.trim())
      .filter((entry) => entry.length > 0)
      .flatMap((entry) => {
        const parts = entry.split('/')
        if (parts.length === 2 && parts[0] && parts[1]) {
          return [{ coordinate: entry, name: parts[1], namespace: parts[0] }]
        }
        return []
      })
  }, [])

  const {
    panelState,
    taskDescription,
    setTaskDescription,
    provider,
    setProvider,
    config,
    selectedSkills,
    error,
    handleToggleSkill,
    handleAddSkill,
    handleGetRecommendations,
    handleStartComparison,
    handleCancelComparison,
    handleReset,
    allSkills,
    statusData,
    maxSelect,
    apiKeyOk,
    isStartingComparison,
    isCancellingComparison,
  } = useForkprobeWorkbench({ preselectedSkills })

  const navigate = useNavigate()

  const [activeTabIdx, setActiveTabIdx] = useState(0)
  const [historyOpen, setHistoryOpen] = useState(false)

  // Resetting the workbench must also clear the selected tab, otherwise a fresh
  // run with fewer results can show a stale/wrong candidate on completion.
  const handleResetWorkbench = useCallback(() => {
    handleReset()
    setActiveTabIdx(0)
  }, [handleReset])

  // Derived
  const taskLen = taskDescription.trim().length
  const taskTooShort = taskLen < 3

  const isSelecting = panelState === 'SELECTING'
  const isRecommending = panelState === 'RECOMMENDING'
  const isRunning = panelState === 'RUNNING'
  const isCompleted = panelState === 'COMPLETED'
  const isIdle = panelState === 'IDLE'
  const showTaskInput = isIdle || isSelecting

  // All selected skills — including failed or still-running ones — stay visible so a
  // candidate is never silently dropped from the left column when the run ends.
  const allResults: CandidateResult[] = statusData?.results ?? []
  const runningResults: CandidateResult[] = allResults
  const activeResult = allResults[activeTabIdx] ?? null

  const totalResults = allResults.length
  const totalTokens = allResults.reduce((s, r) => s + (r.tokensUsed || 0), 0)
  const totalLatency = allResults.reduce((s, r) => s + (r.latencySeconds || 0), 0)

  // Direct follow-up action for the currently viewed candidate — no winner-selection step.
  const activeLink = activeResult ? resolveSkillLink(activeResult) : null

  // --- Helper: skill tab buttons (left sidebar) ---
  const SkillTabs = ({
    skills,
    onTabClick,
    activeIdx,
  }: {
    skills: CandidateResult[]
    onTabClick: (idx: number) => void
    activeIdx: number
  }) => (
    <div className="space-y-1" role="tablist" aria-label={t('forkprobe.candidateSkill')}>
      {skills.map((r, i) => {
        const isActive = i === activeIdx
        return (
          <button
            key={r.skillCoordinate}
            type="button"
            role="tab"
            aria-selected={isActive}
            onClick={() => onTabClick(i)}
            className={cn(
              'w-full text-left px-3 py-2.5 rounded-lg border text-sm transition-all',
              isActive
                ? 'border-primary/40 bg-primary/5 ring-1 ring-primary/10'
                : 'border-transparent hover:bg-muted/50',
            )}
          >
            <div className="flex items-center justify-between gap-2">
              <div className="flex-1 min-w-0">
                <div className="font-medium truncate" style={{ color: 'hsl(var(--foreground))' }}>
                  {r.skillName}
                </div>
                {r.skillApplied !== null && (
                  <div className="mt-1.5">
                    <SkillAppliedBadge skillApplied={r.skillApplied} />
                  </div>
                )}
              </div>
              <ChevronRight
                className={cn(
                  'w-4 h-4 shrink-0 transition-transform',
                  isActive && 'text-primary',
                )}
              />
            </div>
          </button>
        )
      })}
    </div>
  )

  // --- Step indicator ---
  const steps = [
    { num: 1, label: t('forkprobe.stepTask'), active: panelState !== 'IDLE' },
    { num: 2, label: t('forkprobe.stepRecommend'), active: isSelecting || isRunning || isCompleted },
    { num: 3, label: t('forkprobe.stepRun'), active: isRunning || isCompleted },
    { num: 4, label: t('forkprobe.stepContinue'), active: isCompleted },
  ]

  return (
    <div className="space-y-6 animate-fade-up">
      {/* ─── TOP BAR: Report header + step indicator ─── */}
      <div
        className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3 p-4 rounded-xl border"
        style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
      >
        <div className="flex items-center gap-4">
          <div className="flex items-center gap-2">
            <span className="text-sm font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
              forkprobe report
            </span>
            {isCompleted && (
              <span className="text-xs text-muted-foreground">
                · {t('forkprobe.reportHint')}
              </span>
            )}
          </div>
          {(isRunning || isCompleted) && (
            <span className="text-xs text-muted-foreground">
              {totalResults} {t('forkprobe.totalResults')}
              {isCompleted && (
                <>
                  {' '}
                  · {totalLatency.toFixed(1)}s · {totalTokens.toLocaleString()} tokens
                </>
              )}
            </span>
          )}

          <Button
            variant="outline"
            size="sm"
            onClick={() => setHistoryOpen(true)}
            className="ml-auto sm:ml-0"
          >
            <History className="w-3.5 h-3.5 mr-1.5" />
            {t('forkprobe.history')}
          </Button>
        </div>

        {/* Step indicator */}
        <div className="flex items-center gap-1.5 text-xs">
          {steps.map((s, i) => (
            <div key={s.num} className="flex items-center gap-1.5">
              <span
                className={cn(
                  'w-6 h-6 rounded-full flex items-center justify-center text-xs font-bold transition-colors',
                  s.active
                    ? 'bg-primary text-white'
                    : 'bg-muted text-muted-foreground',
                )}
              >
                {s.num}
              </span>
              <span
                className={cn(
                  'font-medium',
                  s.active ? 'text-primary' : 'text-muted-foreground',
                )}
              >
                {s.label}
              </span>
              {i < steps.length - 1 && (
                <span className="text-muted-foreground mx-0.5">→</span>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* ─── API key missing banner ─── */}
      {!apiKeyOk && (
        <div className="flex items-start gap-3 p-4 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm">
          <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
          <span>{t('forkprobe.noApiKey')}</span>
        </div>
      )}

      {/* ─── Run-level error banner (e.g. comparison timed out) ─── */}
      {isCompleted && statusData?.error && (
        <div className="flex items-start gap-3 p-4 rounded-xl bg-red-50 border border-red-200 text-red-700 text-sm">
          <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
          <span>{statusData.error}</span>
        </div>
      )}

      {/* ─── THREE-COLUMN BODY ─── */}
      <div className={cn('fp-grid', isCompleted && 'pt-24')}>
        {/* ===== LEFT ===== */}
        <aside className="fp-left">
          {/* IDLE / SELECTING: task input */}
          {showTaskInput && (
            <div className="space-y-4">
              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.rawInput')}
                </strong>
                <div className="text-xs text-muted-foreground mt-0.5">
                  {t('forkprobe.rawInputHint')}
                </div>
              </div>

              <Textarea
                placeholder={t('forkprobe.taskPlaceholder')}
                value={taskDescription}
                onChange={(e) => setTaskDescription(e.target.value)}
                rows={6}
                autoFocus
                className="resize-y"
              />
              <div className="text-xs text-right text-muted-foreground">
                {t('forkprobe.charCount', { n: taskLen })}{taskTooShort && taskLen > 0 && t('forkprobe.atLeast3Chars')}
              </div>

              <div className="space-y-1.5">
                <label
                  htmlFor="forkprobe-provider"
                  className="text-xs font-medium text-muted-foreground"
                >
                  {t('forkprobe.modelOptional')}
                </label>
                <Select value={provider} onValueChange={setProvider}>
                  <SelectTrigger id="forkprobe-provider" className="w-full">
                    <SelectValue placeholder={t('forkprobe.selectModel')} />
                  </SelectTrigger>
                  <SelectContent>
                    {/* default（云端默认）入口由服务端 showDefault 开关控制显隐 */}
                    {(config?.showDefault ?? true) && (
                      <SelectItem value="default">
                        {providerLabel('deepseek', config?.defaultModel ?? '', t('forkprobe.providerLocal'))}
                      </SelectItem>
                    )}
                    {config?.providers.map((p) => (
                      <SelectItem key={p.id} value={p.id}>
                        {providerLabel(p.id, p.model, t('forkprobe.providerLocal'))}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
                <p className="text-xs text-muted-foreground">
                  {t('forkprobe.modelHint')}
                </p>
              </div>

              <Button
                className="w-full"
                onClick={handleGetRecommendations}
                disabled={taskTooShort}
              >
                <Sparkles className="w-4 h-4 mr-2" />
                {t('forkprobe.getRecommendations')}
              </Button>

              <div className="pt-3 mt-3 border-t" style={{ borderColor: 'hsl(var(--border))' }}>
                <SkillSearchBox
                  selected={selectedSkills}
                  onAdd={handleAddSkill}
                  maxSelect={maxSelect}
                />
              </div>
            </div>
          )}

          {/* SELECTING: skill selection as tab buttons */}
          {isSelecting && allSkills.length > 0 && (
            <div className="mt-6 space-y-3">
              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.candidateSkill')}
                </strong>
                <div className="text-xs text-muted-foreground mt-0.5">
                  {t('forkprobe.selectUpTo', { n: maxSelect })}
                </div>
              </div>

              <div className="space-y-1" role="tablist" aria-label={t('forkprobe.candidateSkill')}>
                {allSkills.map((skill: RecommendedSkill) => {
                  const isSelected = selectedSkills.has(skill.coordinate)
                  const atLimit = selectedSkills.size >= maxSelect
                  const disabled = atLimit && !isSelected
                  const link = resolveRecommendedSkillLink(skill)
                  return (
                    <div key={skill.coordinate} className="flex items-stretch gap-1.5">
                      <button
                        type="button"
                        onClick={() => !disabled && handleToggleSkill(skill.coordinate)}
                        disabled={disabled}
                        className={cn(
                          'flex-1 min-w-0 text-left px-3 py-2.5 rounded-lg border text-sm transition-all',
                          isSelected
                            ? 'border-primary/40 bg-primary/5 ring-1 ring-primary/10'
                            : 'border-transparent hover:bg-muted/50',
                          disabled && 'opacity-40 cursor-not-allowed',
                        )}
                      >
                        <div className="flex items-center justify-between gap-2">
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-1.5">
                              <div
                                className="font-medium truncate"
                                style={{ color: 'hsl(var(--foreground))' }}
                              >
                                {skill.name}
                              </div>
                              {skill.needsNetwork && (
                                <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-700 font-medium shrink-0">
                                  {t('forkprobe.needsNetwork')}
                                </span>
                              )}
                            </div>
                            <div className="text-xs text-muted-foreground truncate mt-0.5">
                              {skill.reasonZh || skill.source}
                            </div>
                            {skill.needsNetwork && (
                              <div className="text-xs text-amber-600 truncate mt-0.5">
                                {t('forkprobe.needsNetworkHint')}
                              </div>
                            )}
                          </div>
                          <div
                            className={cn(
                              'w-5 h-5 rounded border-2 flex items-center justify-center shrink-0',
                              isSelected
                                ? 'border-primary bg-primary text-white'
                                : 'border-border',
                            )}
                          >
                            {isSelected && (
                              <svg className="w-3.5 h-3.5" viewBox="0 0 16 16" fill="currentColor">
                                <path d="M13.3 3.3L6 10.6 2.7 7.3 1.3 8.7l4 4c.4.4 1 .4 1.4 0l8-8-1.4-1.4z" />
                              </svg>
                            )}
                          </div>
                        </div>
                      </button>

                      {link && (
                        link.kind === 'detail' ? (
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
                            className="shrink-0 self-stretch px-2.5 rounded-lg text-xs text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
                          >
                            {t('forkprobe.detail')}
                          </button>
                        ) : (
                          <a
                            href={link.href}
                            target="_blank"
                            rel="noopener noreferrer"
                            title={t('forkprobe.viewSource')}
                            className="shrink-0 self-stretch flex items-center px-2.5 rounded-lg text-xs text-muted-foreground hover:text-foreground hover:bg-muted/50 transition-colors"
                          >
                            {t('forkprobe.viewSource')}
                          </a>
                        )
                      )}
                    </div>
                  )
                })}
              </div>

              {error && (
                <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                  {error}
                </div>
              )}

              <Button
                className="w-full"
                onClick={handleStartComparison}
                disabled={selectedSkills.size === 0 || taskTooShort || isStartingComparison}
              >
                {isStartingComparison ? (
                  <>
                    <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                    {t('forkprobe.starting')}
                  </>
                ) : (
                  <>
                    <GitCompare className="w-4 h-4 mr-2" />
                    {t('forkprobe.startCompareWithCount', { n: selectedSkills.size })}
                  </>
                )}
              </Button>

              <button
                type="button"
                onClick={handleResetWorkbench}
                className="w-full text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                {t('forkprobe.reenterTask')}
              </button>
            </div>
          )}

          {/* RECOMMENDING: skeleton */}
          {isRecommending && (
            <div className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" />
                {t('forkprobe.recommending')}
              </div>
              {[1, 2, 3, 4].map((i) => (
                <div
                  key={i}
                  className="h-14 rounded-lg animate-shimmer"
                  style={{ background: 'hsl(var(--muted))' }}
                />
              ))}
            </div>
          )}

          {/* RUNNING: per-skill status */}
          {isRunning && (
            <div className="space-y-4">
              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.rawInput')}
                </strong>
                <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                  {taskDescription.length > 150
                    ? taskDescription.slice(0, 150) + '...'
                    : taskDescription}
                </p>
              </div>

              <div className="space-y-2">
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.runProgress')}
                </strong>
                {runningResults.map((r) => {
                  const done = r.output || r.error
                  return (
                    <div
                      key={r.skillCoordinate}
                      className="flex items-center gap-2 px-3 py-2 rounded-lg bg-muted/50 text-sm"
                    >
                      {done ? (
                        r.error ? (
                          <AlertCircle className="w-4 h-4 text-red-500 shrink-0" />
                        ) : (
                          <CheckCircle2 className="w-4 h-4 text-emerald-500 shrink-0" />
                        )
                      ) : (
                        <Loader2 className="w-4 h-4 text-primary animate-spin shrink-0" />
                      )}
                      <span className="truncate" style={{ color: 'hsl(var(--foreground))' }}>
                        {r.skillName}
                      </span>
                      {r.latencySeconds > 0 && (
                        <span className="text-xs text-muted-foreground shrink-0 ml-auto">
                          {r.latencySeconds.toFixed(1)}s
                        </span>
                      )}
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* COMPLETED: task summary + skill tabs */}
          {isCompleted && (
            <div className="space-y-4">
              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.rawInput')}
                </strong>
                <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                  {taskDescription.length > 200
                    ? taskDescription.slice(0, 200) + '...'
                    : taskDescription}
                </p>
              </div>

              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.candidateSkill')}
                </strong>
                <div className="mt-2">
                  <SkillTabs
                    skills={allResults}
                    onTabClick={setActiveTabIdx}
                    activeIdx={activeTabIdx}
                  />
                </div>
              </div>

              <button
                type="button"
                onClick={handleResetWorkbench}
                className="w-full py-2 text-xs text-muted-foreground hover:text-foreground transition-colors flex items-center justify-center gap-1"
              >
                <RefreshCw className="w-3 h-3" />
                {t('forkprobe.reSelect')}
              </button>
            </div>
          )}
        </aside>

        {/* ===== CENTER ===== */}
        <main className="fp-center">
          {/* IDLE / RECOMMENDING: empty */}
          {(isIdle || isRecommending) && (
            <div className="flex flex-col items-center justify-center h-full min-h-[420px] text-center space-y-3 pt-24">
              <div className="w-16 h-16 rounded-2xl bg-muted flex items-center justify-center">
                <Hash className="w-8 h-8 text-muted-foreground/40" />
              </div>
              <p className="text-sm text-muted-foreground leading-relaxed">
                {t('forkprobe.idleCenterHint1')}<br />
                {t('forkprobe.idleCenterHint2')}
              </p>
            </div>
          )}

          {/* SELECTING: hint */}
          {isSelecting && (
            <div className="flex flex-col items-center justify-center h-full min-h-[420px] text-center space-y-3 pt-24">
              <div className="w-16 h-16 rounded-2xl bg-muted flex items-center justify-center">
                <GitCompare className="w-8 h-8 text-muted-foreground/40" />
              </div>
              <p className="text-sm text-muted-foreground leading-relaxed">
                {t('forkprobe.selectCenterHint1')}<br />
                {t('forkprobe.selectCenterHint2')}
              </p>
            </div>
          )}

          {/* RUNNING: progress */}
          {isRunning && (
            <div className="flex flex-col items-center justify-center h-full min-h-[420px] text-center space-y-4 pt-24">
              <div className="relative w-16 h-16">
                <Loader2 className="w-16 h-16 text-primary/30 animate-spin absolute inset-0" />
                <span className="absolute inset-0 flex items-center justify-center text-lg font-bold text-primary">
                  {runningResults.filter((r) => r.output || r.error).length}/{runningResults.length}
                </span>
              </div>
              <p className="text-sm text-muted-foreground">{t('forkprobe.runInProgress')}</p>
              <Button
                variant="outline"
                size="sm"
                onClick={handleCancelComparison}
                disabled={isCancellingComparison}
              >
                {isCancellingComparison ? (
                  <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
                ) : (
                  <Square className="w-3.5 h-3.5 mr-1" />
                )}
                {t('forkprobe.cancel')}
              </Button>
            </div>
          )}

          {/* COMPLETED: output display */}
          {isCompleted && activeResult && (
            <div className="space-y-4">
              {/* Skill header */}
              <div>
                <div className="flex items-center justify-between">
                  <h3 className="text-lg font-bold" style={{ color: 'hsl(var(--foreground))' }}>
                    {activeResult.skillName}
                  </h3>
                  {activeResult.output && activeLink?.kind === 'detail' && (
                    <Button
                      size="sm"
                      onClick={() =>
                        navigate({
                          to: '/space/$namespace/$slug',
                          params: { namespace: activeLink.namespace, slug: activeLink.slug },
                        })
                      }
                    >
                      {t('forkprobe.goDetail')}
                      <ChevronRight className="w-3.5 h-3.5 ml-1.5" />
                    </Button>
                  )}
                  {activeResult.output && activeLink?.kind === 'external' && (
                    <a href={activeLink.href} target="_blank" rel="noopener noreferrer">
                      <Button size="sm" variant="outline">
                        {t('forkprobe.viewSource')}
                        <ExternalLink className="w-3.5 h-3.5 ml-1.5" />
                      </Button>
                    </a>
                  )}
                </div>
                {activeResult.skillApplied !== null && (
                  <SkillAppliedBadge skillApplied={activeResult.skillApplied} className="mt-1" />
                )}
              </div>

              {/* Stats */}
              <div className="flex items-center gap-3 text-xs text-muted-foreground">
                <span>🎫 {activeResult.tokensUsed.toLocaleString()} tokens</span>
                <span>⏱ {activeResult.latencySeconds.toFixed(1)}s</span>
              </div>

              {/* Output text */}
              {activeResult.error ? (
                <div className="p-4 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                  <p className="font-semibold mb-1">{t('forkprobe.error')}</p>
                  <p>{activeResult.error}</p>
                </div>
              ) : activeResult.output ? (
                <div
                  className="rounded-lg border p-5 max-h-[500px] overflow-y-auto"
                  style={{
                    background: 'hsl(var(--card))',
                    borderColor: 'hsl(var(--border))',
                  }}
                >
                  <ForkprobeOutput content={activeResult.output} className="max-w-[72ch]" />
                </div>
              ) : (
                <div className="p-4 rounded-lg bg-amber-50 border border-amber-200 text-amber-700 text-sm">
                  <p className="font-semibold mb-1">{t('forkprobe.incomplete')}</p>
                  <p>{t('forkprobe.incompleteHint')}</p>
                </div>
              )}

              {/* Deliverable files (sandbox /output) */}
              {activeResult.files && activeResult.files.length > 0 && (
                <ForkprobeFileList files={activeResult.files} />
              )}

              {/* Tab-switch hint */}
              <p
                className="text-xs text-center py-2 rounded-lg"
                style={{ background: 'hsl(var(--muted))', color: 'hsl(var(--muted-foreground))' }}
              >
                {t('forkprobe.viewingHint', { name: activeResult.skillName })}
              </p>
            </div>
          )}

          {isCompleted && !activeResult && (
            <div className="flex flex-col items-center justify-center h-full min-h-[300px] text-center text-sm text-muted-foreground">
              <p>{t('forkprobe.noResults')}</p>
              <button type="button" onClick={handleReset} className="mt-2 text-primary hover:underline">
                {t('forkprobe.reSelect')}
              </button>
            </div>
          )}
        </main>

      </div>

      <ComparisonHistory open={historyOpen} onClose={() => setHistoryOpen(false)} />
    </div>
  )
}
