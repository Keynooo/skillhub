import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  Sparkles,
  Loader2,
  Square,
  History,
  AlertCircle,
  Eraser,
} from 'lucide-react'
import { Button } from '@/shared/ui/button'
import { Textarea } from '@/shared/ui/textarea'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/select'
import { usePipelineWorkbench, MAX_LANES } from './use-pipeline-workbench'
import { PipelineStagePicker } from './pipeline-stage-picker'
import { PipelineProgress } from './pipeline-progress'
import { PipelineSummary } from './pipeline-summary'
import { PipelineHistory } from './pipeline-history'

/** Friendly display names for the known providers; falls back to id. */
const PROVIDER_LABELS: Record<string, string> = {
  glm: 'GLM',
  deepseek: 'DeepSeek',
}

function providerLabel(id: string, model: string, localLabel: string): string {
  const base = id === 'local' ? localLabel : PROVIDER_LABELS[id] ?? id
  return model ? `${base} · ${model}` : base
}

/**
 * The unified forkprobe workbench. One task fans out into parallel lanes —
 * browser-style tabs, one per lane, click + to add (up to MAX_LANES). Each lane
 * is a serial chain of the skills the user picked (0..5); an empty lane runs as
 * the native/baseline reference. Results are shown in the same tab layout.
 */
export function PipelineWorkbench() {
  const { t } = useTranslation()

  const {
    panelState,
    taskDescription,
    setTaskDescription,
    provider,
    setProvider,
    config,
    lanes,
    error,
    allSkills,
    handleAddStage,
    handleRemoveStage,
    handleAddLane,
    handleRemoveLane,
    handleGetRecommendations,
    handleStartPipeline,
    handleCancelPipeline,
    handleReset,
    handleClearAll,
    statusData,
    maxStages,
    apiKeyOk,
    isStartingPipeline,
    isCancellingPipeline,
  } = usePipelineWorkbench()

  const [historyOpen, setHistoryOpen] = useState(false)

  // Derived
  const taskLen = taskDescription.trim().length
  const taskTooShort = taskLen < 3

  const isIdle = panelState === 'IDLE'
  const isRecommending = panelState === 'RECOMMENDING'
  const isSelecting = panelState === 'SELECTING'
  const isRunning = panelState === 'RUNNING'
  const isCompleted = panelState === 'COMPLETED'

  const statusLanes = statusData?.lanes ?? []

  return (
    <div className="mx-auto w-full max-w-6xl space-y-6 animate-fade-up">
      {/* ─── HEADER ─── */}
      <header className="flex items-start justify-between gap-4">
        <div>
          <h1
            className="text-2xl font-bold tracking-tight"
            style={{ color: 'hsl(var(--foreground))' }}
          >
            forkprobe
          </h1>
          <p className="text-sm mt-1" style={{ color: 'hsl(var(--muted-foreground))' }}>
            {t('forkprobe.subtitle')}
          </p>
        </div>
        <Button variant="outline" size="sm" onClick={() => setHistoryOpen(true)}>
          <History className="w-4 h-4 mr-1.5" />
          {t('forkprobe.history')}
        </Button>
      </header>

      {/* ─── API key missing banner ─── */}
      {!apiKeyOk && (
        <div className="flex items-start gap-3 p-4 rounded-xl bg-amber-50 border border-amber-200 text-amber-800 text-sm">
          <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
          <span>{t('forkprobe.noApiKey')}</span>
        </div>
      )}

      {/* ─── Run-level error banner ─── */}
      {isCompleted && statusData?.error && (
        <div className="flex items-start gap-3 p-4 rounded-xl bg-red-50 border border-red-200 text-red-700 text-sm">
          <AlertCircle className="w-5 h-5 shrink-0 mt-0.5" />
          <span>{statusData.error}</span>
        </div>
      )}

      {/* ─── IDLE / RECOMMENDING: focused task input ─── */}
      {(isIdle || isRecommending) && (
        <div className="mx-auto max-w-2xl pt-8">
          <div
            className="rounded-2xl border p-6 space-y-4 shadow-sm"
            style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
          >
            <div>
              <label
                htmlFor="forkprobe-task"
                className="text-sm font-medium"
                style={{ color: 'hsl(var(--foreground))' }}
              >
                {t('forkprobe.taskLabel')}
              </label>
              <Textarea
                id="forkprobe-task"
                placeholder={t('forkprobe.taskPlaceholder')}
                value={taskDescription}
                onChange={(e) => setTaskDescription(e.target.value)}
                rows={6}
                autoFocus
                className="mt-2 resize-y"
              />
              <div className="mt-1.5 text-xs text-right text-muted-foreground">
                {t('forkprobe.charCount', { n: taskLen })}
                {taskTooShort && taskLen > 0 && t('forkprobe.atLeast3Chars')}
              </div>
            </div>

            <div className="space-y-1.5">
              <label htmlFor="forkprobe-provider" className="text-xs font-medium text-muted-foreground">
                {t('forkprobe.modelOptional')}
              </label>
              <Select value={provider} onValueChange={setProvider}>
                <SelectTrigger id="forkprobe-provider" className="w-full">
                  <SelectValue placeholder={t('forkprobe.selectModel')} />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="default">
                    {providerLabel('deepseek', config?.defaultModel ?? '', t('forkprobe.providerLocal'))}
                  </SelectItem>
                  {config?.providers.map((p) => (
                    <SelectItem key={p.id} value={p.id}>
                      {providerLabel(p.id, p.model, t('forkprobe.providerLocal'))}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>

            <Button
              className="w-full"
              onClick={handleGetRecommendations}
              disabled={taskTooShort || isRecommending}
            >
              {isRecommending ? (
                <>
                  <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                  {t('forkprobe.recommending')}
                </>
              ) : (
                <>
                  <Sparkles className="w-4 h-4 mr-2" />
                  {t('forkprobe.getRecommendations')}
                </>
              )}
            </Button>
          </div>

          <p className="text-xs text-center mt-4" style={{ color: 'hsl(var(--muted-foreground))' }}>
            {t('forkprobe.idleFooter')}
          </p>
        </div>
      )}

      {/* ─── SELECTING: task bar + lane tabs + skill picker ─── */}
      {isSelecting && (
        <div className="space-y-5">
          {/* Task bar — same visual weight as the IDLE card, with the primary
              action still reading as primary (large, branded, right-aligned) */}
          <div
            className="rounded-2xl border p-5 space-y-3 shadow-sm"
            style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
          >
            <div className="flex items-center justify-between gap-3">
              <label
                htmlFor="forkprobe-task-edit"
                className="block text-xs font-medium uppercase tracking-wide"
                style={{ color: 'hsl(var(--muted-foreground))' }}
              >
                {t('forkprobe.taskLabel')}
              </label>
              <Button
                variant="ghost"
                size="sm"
                onClick={handleClearAll}
                title={t('forkprobe.clearAllHint')}
              >
                <Eraser className="w-3.5 h-3.5 mr-1.5" />
                {t('forkprobe.clearAll')}
              </Button>
            </div>
            <Textarea
              id="forkprobe-task-edit"
              value={taskDescription}
              onChange={(e) => setTaskDescription(e.target.value)}
              rows={2}
              className="resize-y"
            />
            <div className="flex items-center justify-between gap-3">
              <span className="text-xs text-muted-foreground">
                {t('forkprobe.charCount', { n: taskLen })}
                {taskTooShort && taskLen > 0 && t('forkprobe.atLeast3Chars')}
              </span>
              <div className="flex items-center gap-3">
                <div className="w-52">
                  <Select value={provider} onValueChange={setProvider}>
                    <SelectTrigger className="h-9 text-xs">
                      <SelectValue placeholder={t('forkprobe.selectModel')} />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="default">
                        {providerLabel('deepseek', config?.defaultModel ?? '', t('forkprobe.providerLocal'))}
                      </SelectItem>
                      {config?.providers.map((p) => (
                        <SelectItem key={p.id} value={p.id}>
                          {providerLabel(p.id, p.model, t('forkprobe.providerLocal'))}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
                <Button
                  onClick={handleGetRecommendations}
                  disabled={taskTooShort || isRecommending}
                >
                  {isRecommending ? (
                    <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                  ) : (
                    <Sparkles className="w-4 h-4 mr-2" />
                  )}
                  {t('forkprobe.getRecommendations')}
                </Button>
              </div>
            </div>
          </div>

          {/* Browser-style lane tabs + click-based skill picker */}
          <PipelineStagePicker
            lanes={lanes}
            allSkills={allSkills}
            onAdd={handleAddStage}
            onRemove={handleRemoveStage}
            onAddLane={handleAddLane}
            onRemoveLane={handleRemoveLane}
            maxStages={maxStages}
            maxLanes={MAX_LANES}
          />

          {error && (
            <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
              {error}
            </div>
          )}

          {/* Start — each lane (tab) runs as its own result channel */}
          <div className="flex justify-center">
            <Button
              size="lg"
              onClick={handleStartPipeline}
              disabled={taskTooShort || isStartingPipeline}
            >
              {isStartingPipeline ? (
                <>
                  <Loader2 className="w-4 h-4 mr-2 animate-spin" />
                  {t('forkprobe.starting')}
                </>
              ) : (
                <>
                  <Sparkles className="w-4 h-4 mr-2" />
                  {t('forkprobe.startPipeline')}
                </>
              )}
            </Button>
          </div>
        </div>
      )}

      {/* ─── RUNNING ─── */}
      {isRunning && (
        <div className="space-y-4">
          <PipelineProgress lanes={statusLanes} />
          <div className="flex justify-center">
            <Button
              variant="outline"
              size="sm"
              onClick={handleCancelPipeline}
              disabled={isCancellingPipeline}
            >
              {isCancellingPipeline ? (
                <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" />
              ) : (
                <Square className="w-3.5 h-3.5 mr-1" />
              )}
              {t('forkprobe.cancel')}
            </Button>
          </div>
        </div>
      )}

      {/* ─── COMPLETED ─── */}
      {isCompleted && statusLanes.length > 0 && (
        <div className="space-y-4">
          <PipelineSummary lanes={statusLanes} />
          <div className="flex justify-center">
            <Button variant="outline" size="sm" onClick={handleReset}>
              {t('forkprobe.reSelect')}
            </Button>
          </div>
        </div>
      )}

      {isCompleted && statusLanes.length === 0 && (
        <div className="flex flex-col items-center justify-center py-16 text-center text-sm text-muted-foreground">
          <p>{t('forkprobe.noResults')}</p>
          <button type="button" onClick={handleReset} className="mt-2 text-primary hover:underline">
            {t('forkprobe.reSelect')}
          </button>
        </div>
      )}

      <PipelineHistory open={historyOpen} onClose={() => setHistoryOpen(false)} />
    </div>
  )
}
