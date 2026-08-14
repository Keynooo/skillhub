import { useState, useMemo } from 'react'
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
} from 'lucide-react'
import { useNavigate } from '@tanstack/react-router'
import { Button } from '@/shared/ui/button'
import { Textarea } from '@/shared/ui/textarea'
import { cn } from '@/shared/lib/utils'
import { useForkprobeWorkbench } from '@/features/forkprobe/use-forkprobe-workbench'
import { SkillSearchBox } from '@/features/forkprobe/skill-search-box'
import type { CandidateResult } from '@/features/forkprobe/forkprobe-api'
import type { RecommendedSkill } from '@/features/forkprobe/forkprobe-api'
import { resolveSkillLink } from '@/features/forkprobe/forkprobe-api'

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
    <div className="space-y-1" role="tablist" aria-label="候选 skill">
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
                {r.appliedReason && (
                  <div className="text-xs text-muted-foreground truncate mt-0.5">
                    {r.appliedReason}
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
    { num: 1, label: '任务', active: panelState !== 'IDLE' },
    { num: 2, label: '推荐', active: isSelecting || isRunning || isCompleted },
    { num: 3, label: '试跑', active: isRunning || isCompleted },
    { num: 4, label: '继续', active: isCompleted },
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
                · 本地生成 · 选择结果后继续执行
              </span>
            )}
          </div>
          {(isRunning || isCompleted) && (
            <span className="text-xs text-muted-foreground">
              {totalResults} 路结果
              {isCompleted && (
                <>
                  {' '}
                  · {totalLatency.toFixed(1)}s · {totalTokens.toLocaleString()} tokens
                </>
              )}
            </span>
          )}
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
                  原始输入
                </strong>
                <div className="text-xs text-muted-foreground mt-0.5">
                  描述你的任务，系统将从 catalog 中推荐合适的技能候选。
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
                {taskLen} 字符{taskTooShort && taskLen > 0 && '（至少 3 个）'}
              </div>

              <Button
                className="w-full"
                onClick={handleGetRecommendations}
                disabled={taskTooShort}
              >
                <Sparkles className="w-4 h-4 mr-2" />
                获取推荐
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
                  候选 skill
                </strong>
                <div className="text-xs text-muted-foreground mt-0.5">
                  选择最多 {maxSelect} 个要对比的技能
                </div>
              </div>

              <div className="space-y-1" role="tablist" aria-label="候选 skill">
                {allSkills.map((skill: RecommendedSkill) => {
                  const isSelected = selectedSkills.has(skill.coordinate)
                  const atLimit = selectedSkills.size >= maxSelect
                  const disabled = atLimit && !isSelected
                  return (
                    <button
                      key={skill.coordinate}
                      type="button"
                      onClick={() => !disabled && handleToggleSkill(skill.coordinate)}
                      disabled={disabled}
                      className={cn(
                        'w-full text-left px-3 py-2.5 rounded-lg border text-sm transition-all',
                        isSelected
                          ? 'border-primary/40 bg-primary/5 ring-1 ring-primary/10'
                          : 'border-transparent hover:bg-muted/50',
                        disabled && 'opacity-40 cursor-not-allowed',
                      )}
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex-1 min-w-0">
                          <div
                            className="font-medium truncate"
                            style={{ color: 'hsl(var(--foreground))' }}
                          >
                            {skill.name}
                          </div>
                          <div className="text-xs text-muted-foreground truncate mt-0.5">
                            {skill.reasonZh || skill.source}
                          </div>
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
                    启动中...
                  </>
                ) : (
                  <>
                    <GitCompare className="w-4 h-4 mr-2" />
                    开始对比（{selectedSkills.size} 个技能）
                  </>
                )}
              </Button>

              <button
                type="button"
                onClick={handleReset}
                className="w-full text-xs text-muted-foreground hover:text-foreground transition-colors"
              >
                重新输入任务
              </button>
            </div>
          )}

          {/* RECOMMENDING: skeleton */}
          {isRecommending && (
            <div className="space-y-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Loader2 className="w-4 h-4 animate-spin" />
                正在分析任务，搜索合适技能...
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
                  原始输入
                </strong>
                <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                  {taskDescription.length > 150
                    ? taskDescription.slice(0, 150) + '...'
                    : taskDescription}
                </p>
              </div>

              <div className="space-y-2">
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  试跑进度
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
                  原始输入
                </strong>
                <p className="text-xs text-muted-foreground mt-1 leading-relaxed">
                  {taskDescription.length > 200
                    ? taskDescription.slice(0, 200) + '...'
                    : taskDescription}
                </p>
              </div>

              <div>
                <strong className="text-sm" style={{ color: 'hsl(var(--foreground))' }}>
                  候选 skill
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
                onClick={handleReset}
                className="w-full py-2 text-xs text-muted-foreground hover:text-foreground transition-colors flex items-center justify-center gap-1"
              >
                <RefreshCw className="w-3 h-3" />
                重新选择
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
                输入任务并获取推荐后，<br />
                每个技能的输出将在这里展示。
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
                选择要对比的技能后，<br />
                点击"开始对比"启动试跑。
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
              <p className="text-sm text-muted-foreground">试跑中...</p>
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
                取消
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
                      去详情页
                      <ChevronRight className="w-3.5 h-3.5 ml-1.5" />
                    </Button>
                  )}
                  {activeResult.output && activeLink?.kind === 'external' && (
                    <a href={activeLink.href} target="_blank" rel="noopener noreferrer">
                      <Button size="sm" variant="outline">
                        查看源
                        <ExternalLink className="w-3.5 h-3.5 ml-1.5" />
                      </Button>
                    </a>
                  )}
                </div>
                {activeResult.appliedReason && (
                  <p className="text-xs text-muted-foreground mt-1">{activeResult.appliedReason}</p>
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
                  <p className="font-semibold mb-1">执行失败</p>
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
                  <div
                    className="text-base leading-7 whitespace-pre-wrap max-w-[72ch]"
                    style={{ color: 'hsl(var(--foreground))' }}
                  >
                    {activeResult.output}
                  </div>
                </div>
              ) : (
                <div className="p-4 rounded-lg bg-amber-50 border border-amber-200 text-amber-700 text-sm">
                  <p className="font-semibold mb-1">未完成</p>
                  <p>该 skill 未返回结果（执行超时或仍在运行中）。</p>
                </div>
              )}

              {/* Tab-switch hint */}
              <p
                className="text-xs text-center py-2 rounded-lg"
                style={{ background: 'hsl(var(--muted))', color: 'hsl(var(--muted-foreground))' }}
              >
                正在查看 {activeResult.skillName}，点击左侧其他候选可切换输出。
              </p>
            </div>
          )}

          {isCompleted && !activeResult && (
            <div className="flex flex-col items-center justify-center h-full min-h-[300px] text-center text-sm text-muted-foreground">
              <p>没有结果可显示。</p>
              <button type="button" onClick={handleReset} className="mt-2 text-primary hover:underline">
                重新选择
              </button>
            </div>
          )}
        </main>

      </div>
    </div>
  )
}
