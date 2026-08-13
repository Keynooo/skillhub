'use client'

import { useCallback } from 'react'
import { useTranslation } from 'react-i18next'
import { X, GitCompare, RefreshCw, AlertCircle, Sparkles, LogIn } from 'lucide-react'
import { Link } from '@tanstack/react-router'
import { Button } from '@/shared/ui/button'
import { Textarea } from '@/shared/ui/textarea'
import { cn } from '@/shared/lib/utils'
import { useComparisonPanel } from './comparison-panel-context'
import { useForkprobeWorkbench } from './use-forkprobe-workbench'
import { ComparisonResultCard } from './comparison-result-card'
import { ComparisonProgress } from './comparison-progress'
import { SkillSelectionList } from './skill-selection-list'
import { useAuth } from '@/features/auth/use-auth'
import type { CandidateResult } from './forkprobe-api'

/**
 * Slide-out comparison panel — the quick-access forkprobe UI.
 *
 * State machine is delegated to {@link useForkprobeWorkbench} so this panel
 * and the full-screen workbench page share the same logic.
 */
export function ComparisonPanel() {
  const { t } = useTranslation()
  const { isOpen, preselectedSkill, closePanel } = useComparisonPanel()

  const { user } = useAuth()

  const {
    panelState,
    taskDescription,
    setTaskDescription,
    selectedSkills,
    error,
    handleToggleSkill,
    handleGetRecommendations,
    handleStartComparison,
    handleReset,
    recommendations,
    allSkills,
    statusData,
    maxSelect,
    apiKeyOk,
    isStartingComparison,
  } = useForkprobeWorkbench({ preselectedSkill })

  // --- Derived ---
  const statusDataForState = statusData

  const handleClose = useCallback(() => {
    closePanel()
  }, [closePanel])

  if (!isOpen) return null

  const taskLen = taskDescription.trim().length
  const taskTooShort = taskLen < 3

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/30 z-40 transition-opacity duration-300"
        onClick={handleClose}
        aria-hidden="true"
      />

      {/* Panel */}
      <div
        className={cn(
          'fixed right-0 top-0 bottom-0 w-full sm:w-[520px] z-50',
          'bg-card border-l border-border shadow-2xl',
          'flex flex-col',
          'animate-panel-slide-in',
        )}
      >
        {/* Header */}
        <div
          className="flex items-center justify-between px-5 py-4 border-b shrink-0"
          style={{ borderColor: 'hsl(var(--border))' }}
        >
          <div className="flex items-center gap-2">
            <GitCompare className="w-5 h-5 text-primary" />
            <h2 className="text-lg font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
              {t('forkprobe.title')}
            </h2>
          </div>
          <button
            onClick={handleClose}
            className="p-2 rounded-lg hover:bg-secondary transition-colors"
            aria-label={t('forkprobe.closePanel')}
          >
            <X className="w-5 h-5" style={{ color: 'hsl(var(--muted-foreground))' }} />
          </button>
        </div>

        {/* Body */}
        <div className="flex-1 overflow-y-auto px-5 py-4 space-y-4">
          {/* API key missing */}
          {!apiKeyOk && (
            <div className="flex items-start gap-3 p-4 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm">
              <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
              <span>{t('forkprobe.noApiKey')}</span>
            </div>
          )}

          {/* Task input (IDLE) */}
          {panelState === 'IDLE' && (
            <div className="space-y-4">
              {!user ? (
                <div className="flex flex-col items-center gap-4 py-8 px-4 text-center">
                  <div className="w-16 h-16 rounded-full bg-primary/10 flex items-center justify-center">
                    <LogIn className="w-8 h-8 text-primary" />
                  </div>
                  <div>
                    <h3 className="text-base font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
                      登录后使用技能对比
                    </h3>
                    <p className="text-sm mt-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
                      登录 SkillHub 即可使用 AI 对多个技能进行对比评测
                    </p>
                  </div>
                  <Link
                    to="/login"
                    search={{ returnTo: window.location.pathname + window.location.search }}
                    className="inline-flex items-center gap-2 px-6 py-2.5 rounded-full bg-primary text-white text-sm font-medium hover:opacity-90 transition-opacity"
                  >
                    <LogIn className="w-4 h-4" />
                    去登录 / 注册
                  </Link>
                </div>
              ) : (
                <>
                  {preselectedSkill && (
                    <div className="flex items-center gap-2 px-3 py-2 rounded-lg bg-primary/5 border border-primary/20 text-sm">
                      <span className="text-muted-foreground">已选技能：</span>
                      <span className="font-medium text-primary">{preselectedSkill.name}</span>
                      <span className="text-xs text-muted-foreground font-mono">{preselectedSkill.coordinate}</span>
                    </div>
                  )}

                  <div className="space-y-2">
                    <label
                      className="text-sm font-medium block"
                      style={{ color: 'hsl(var(--foreground))' }}
                    >
                      任务描述
                    </label>
                    <Textarea
                      placeholder={t('forkprobe.taskPlaceholder')}
                      value={taskDescription}
                      onChange={(e) => setTaskDescription(e.target.value)}
                      rows={4}
                      autoFocus
                    />
                    <div className="flex items-center justify-between">
                      <span className={cn(
                        'text-xs',
                        taskTooShort && taskLen > 0
                          ? 'text-amber-600'
                          : 'text-muted-foreground',
                      )}>
                        {taskLen === 0
                          ? '输入至少 3 个字符以获取推荐'
                          : taskTooShort
                            ? `还需 ${3 - taskLen} 个字符`
                            : '✓ 可以获取推荐了'}
                      </span>
                      <span className="text-xs text-muted-foreground">{taskLen}/3+</span>
                    </div>
                  </div>

                  <Button
                    className="w-full"
                    onClick={handleGetRecommendations}
                    disabled={taskTooShort}
                  >
                    <Sparkles className="w-4 h-4 mr-2" />
                    {t('forkprobe.getRecommendations')}
                  </Button>
                </>
              )}
            </div>
          )}

          {/* Loading skeleton (RECOMMENDING) */}
          {panelState === 'RECOMMENDING' && (
            <div className="space-y-4">
              <p className="text-sm" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {t('forkprobe.recommending')}
              </p>
              {[1, 2, 3].map((i) => (
                <div
                  key={i}
                  className="h-16 rounded-xl animate-shimmer"
                  style={{ background: 'hsl(var(--secondary))' }}
                />
              ))}
            </div>
          )}

          {/* Selection (SELECTING) */}
          {panelState === 'SELECTING' && (
            <>
              <div className="space-y-2">
                <label
                  className="text-sm font-medium block"
                  style={{ color: 'hsl(var(--foreground))' }}
                >
                  任务描述
                </label>
                <Textarea
                  placeholder={t('forkprobe.taskPlaceholder')}
                  value={taskDescription}
                  onChange={(e) => setTaskDescription(e.target.value)}
                  rows={3}
                />
              </div>

              <div className="flex items-center justify-between mb-1">
                <span className="text-sm font-medium" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.selectSkills')}
                </span>
                <span
                  className={cn(
                    'text-xs font-medium px-2 py-0.5 rounded-full',
                    selectedSkills.size >= maxSelect
                      ? 'bg-red-100 text-red-600'
                      : 'bg-secondary',
                  )}
                  style={selectedSkills.size < maxSelect ? { color: 'hsl(var(--muted-foreground))' } : undefined}
                >
                  {selectedSkills.size}/{maxSelect}
                </span>
              </div>

              <SkillSelectionList
                recommendations={recommendations}
                allSkills={allSkills}
                selected={selectedSkills}
                onToggle={handleToggleSkill}
                maxSelect={maxSelect}
              />

              {error && (
                <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                  {error}
                </div>
              )}

              <Button
                className="w-full"
                onClick={handleStartComparison}
                disabled={selectedSkills.size === 0 || isStartingComparison || !apiKeyOk}
              >
                {!apiKeyOk ? (
                  <>
                    <AlertCircle className="w-4 h-4 mr-2" />
                    {t('forkprobe.noApiKey')}
                  </>
                ) : isStartingComparison ? (
                  t('forkprobe.recommending')
                ) : (
                  <>
                    <GitCompare className="w-4 h-4 mr-2" />
                    {t('forkprobe.startCompare')} ({selectedSkills.size})
                  </>
                )}
              </Button>
            </>
          )}

          {/* Progress (RUNNING) */}
          {panelState === 'RUNNING' && statusDataForState && (
            <ComparisonProgress results={statusDataForState.results} />
          )}

          {/* Results (COMPLETED) */}
          {panelState === 'COMPLETED' && statusDataForState && (
            <div className="space-y-4">
              <div className="flex items-center justify-between">
                <span className="text-sm font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
                  {t('forkprobe.completed')}
                </span>
                <Button variant="outline" size="sm" onClick={handleReset}>
                  <RefreshCw className="w-3.5 h-3.5 mr-1.5" />
                  {t('forkprobe.reSelect')}
                </Button>
              </div>

              {statusDataForState.error && (
                <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
                  {statusDataForState.error}
                </div>
              )}

              <div className="text-xs italic px-3 py-2 rounded-lg bg-secondary" style={{ color: 'hsl(var(--muted-foreground))' }}>
                任务：{taskDescription.length > 100 ? taskDescription.slice(0, 100) + '...' : taskDescription}
              </div>

              {statusDataForState.results.map((result: CandidateResult, idx: number) => (
                <ComparisonResultCard
                  key={result.skillCoordinate}
                  result={result}
                  index={idx}
                />
              ))}

              <Button variant="outline" className="w-full" onClick={handleReset}>
                <RefreshCw className="w-4 h-4 mr-2" />
                {t('forkprobe.reSelect')}
              </Button>
            </div>
          )}
        </div>
      </div>
    </>
  )
}
