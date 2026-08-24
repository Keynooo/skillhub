'use client'

import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { createPortal } from 'react-dom'
import { X, History, Loader2, ChevronRight, ArrowLeft, Trash2 } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import {
  useForkprobeHistory,
  useForkprobeHistoryDetail,
  useDeleteForkprobeHistory,
} from './use-forkprobe-queries'
import { ComparisonResultCard } from './comparison-result-card'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import type { ComparisonHistoryItem, ComparisonStatusResponse } from './forkprobe-api'

const STATUS_KEYS: Record<string, string> = {
  COMPLETED: 'forkprobe.statusCompleted',
  FAILED: 'forkprobe.statusFailed',
  CANCELLED: 'forkprobe.statusCancelled',
}

function formatTime(iso: string | null, locale?: string): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString(locale || 'en', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/**
 * Modal listing the user's persisted comparison runs, with drill-in to view a
 * past run's full results. Persisted rows survive the in-memory store's TTL, so
 * this is how users revisit results after leaving the workbench.
 */
export function ComparisonHistory({
  open,
  onClose,
}: {
  open: boolean
  onClose: () => void
}) {
  const { t } = useTranslation()
  const { data: history, isLoading } = useForkprobeHistory(20, open)
  const [selectedItem, setSelectedItem] = useState<ComparisonHistoryItem | null>(null)
  const { data: detail, isLoading: detailLoading } = useForkprobeHistoryDetail(
    open ? (selectedItem?.comparisonId ?? null) : null,
  )

  if (!open) return null

  return createPortal(
    <>
      {/* Backdrop: dim the page; clicking it closes the drawer */}
      <div className="fixed inset-0 bg-black/50 z-40" aria-hidden="true" onClick={onClose} />

      {/* Drawer: slides in from the right, keeping the workbench visible */}
      <aside
        className="fixed inset-y-0 right-0 z-50 w-full max-w-lg flex flex-col border-l bg-card animate-panel-slide-in"
        style={{ borderColor: 'hsl(var(--border))' }}
        role="dialog"
        aria-modal="true"
      >
          {/* Header */}
          <div className="flex items-center justify-between px-5 py-4 border-b shrink-0">
            <div className="flex items-center gap-2 min-w-0">
              {selectedItem && (
                <button
                  onClick={() => setSelectedItem(null)}
                  className="p-1.5 rounded-lg hover:bg-secondary transition-colors"
                  aria-label={t('forkprobe.backToList')}
                >
                  <ArrowLeft className="w-4 h-4" />
                </button>
              )}
              <History className="w-5 h-5 text-primary shrink-0" />
              <h2 className="text-lg font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
                {selectedItem ? t('forkprobe.historyDetail') : t('forkprobe.history')}
              </h2>
            </div>
            <button
              onClick={onClose}
              className="p-2 rounded-lg hover:bg-secondary transition-colors"
              aria-label={t('forkprobe.close')}
            >
              <X className="w-5 h-5" style={{ color: 'hsl(var(--muted-foreground))' }} />
            </button>
          </div>

          {/* Body */}
          <div className="flex-1 overflow-y-auto px-5 py-4">
            {!selectedItem ? (
              <HistoryList
                history={history}
                isLoading={isLoading}
                onSelect={setSelectedItem}
              />
            ) : (
              <HistoryDetail item={selectedItem} detail={detail} isLoading={detailLoading} />
            )}
          </div>
      </aside>
    </>,
    document.body,
  )
}

function HistoryList({
  history,
  isLoading,
  onSelect,
}: {
  history: ComparisonHistoryItem[] | undefined
  isLoading: boolean
  onSelect: (item: ComparisonHistoryItem) => void
}) {
  const { t, i18n } = useTranslation()
  const [selecting, setSelecting] = useState(false)
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [confirmOpen, setConfirmOpen] = useState(false)
  const deleteMutation = useDeleteForkprobeHistory()
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="w-6 h-6 animate-spin text-primary" />
      </div>
    )
  }

  if (!history || history.length === 0) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <History className="w-10 h-10 text-muted-foreground/40" />
        <p className="text-sm text-muted-foreground mt-3">{t('forkprobe.noHistory')}</p>
      </div>
    )
  }

  const allSelected = selected.size === history.length
  const toggleAll = () =>
    setSelected(allSelected ? new Set() : new Set(history.map((i) => i.comparisonId)))
  const toggleOne = (id: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  const handleDelete = async () => {
    const ids = [...selected]
    await Promise.all(ids.map((id) => deleteMutation.mutateAsync(id)))
    setSelected(new Set())
    setSelecting(false)
  }

  const exitSelecting = () => {
    setSelecting(false)
    setSelected(new Set())
  }

  return (
    <div>
      {/* Toolbar: plain "select" entry by default; select-all + delete in selection mode */}
      <div className="flex items-center justify-between mb-3 h-8">
        {selecting ? (
          <>
            <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer select-none">
              <input
                type="checkbox"
                checked={allSelected}
                onChange={toggleAll}
                className="w-3.5 h-3.5 accent-[hsl(var(--primary))]"
                aria-label={t('forkprobe.selectAll')}
              />
              {t('forkprobe.selectAll')}
            </label>
            <div className="flex items-center gap-2">
              {selected.size > 0 && (
                <button
                  type="button"
                  onClick={() => setConfirmOpen(true)}
                  disabled={deleteMutation.isPending}
                  className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium bg-red-50 text-red-600 hover:bg-red-100 transition-colors disabled:opacity-50"
                >
                  {deleteMutation.isPending ? (
                    <Loader2 className="w-3.5 h-3.5 animate-spin" />
                  ) : (
                    <Trash2 className="w-3.5 h-3.5" />
                  )}
                  {t('forkprobe.deleteSelected', { n: selected.size })}
                </button>
              )}
              <button
                type="button"
                onClick={exitSelecting}
                className="px-3 py-1.5 rounded-lg text-xs font-medium text-muted-foreground hover:bg-secondary transition-colors"
              >
                {t('forkprobe.cancel')}
              </button>
            </div>
          </>
        ) : (
          <div className="ml-auto">
            <button
              type="button"
              onClick={() => setSelecting(true)}
              className="px-3 py-1.5 rounded-lg text-xs font-medium text-muted-foreground hover:bg-secondary transition-colors"
            >
              {t('forkprobe.select')}
            </button>
          </div>
        )}
      </div>

      <ul className="space-y-2">
        {history.map((item) => {
          const status = item.status
          const isFailed = status === 'FAILED'
          const isCancelled = status === 'CANCELLED'
          return (
            <li key={item.comparisonId} className="flex items-center gap-2">
              {selecting && (
                <input
                  type="checkbox"
                  checked={selected.has(item.comparisonId)}
                  onChange={() => toggleOne(item.comparisonId)}
                  className="w-3.5 h-3.5 shrink-0 accent-[hsl(var(--primary))]"
                  aria-label={t('forkprobe.selectAll')}
                />
              )}
              <button
                type="button"
                onClick={() => onSelect(item)}
                className="flex-1 min-w-0 text-left px-4 py-3 rounded-xl border hover:bg-muted/50 transition-colors"
                style={{ borderColor: 'hsl(var(--border))' }}
              >
                <div className="flex items-center justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div
                      className="text-sm font-medium truncate"
                      style={{ color: 'hsl(var(--foreground))' }}
                    >
                      {item.taskDescription}
                    </div>
                    <div className="flex items-center gap-2 mt-1 text-xs text-muted-foreground">
                      <span
                        className={cn(
                          'px-1.5 py-0.5 rounded-full font-medium',
                          isFailed && 'bg-red-100 text-red-600',
                          isCancelled && 'bg-gray-100 text-gray-500',
                          !isFailed && !isCancelled && 'bg-emerald-100 text-emerald-700',
                        )}
                      >
                        {STATUS_KEYS[status] ? t(STATUS_KEYS[status]) : status}
                      </span>
                      <span>{t('forkprobe.skillCount', { n: item.skillCount })}</span>
                      {item.provider && <span>· {item.provider}</span>}
                      <span>· {formatTime(item.createdAt, i18n.resolvedLanguage)}</span>
                    </div>
                  </div>
                  <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
                </div>
              </button>
            </li>
          )
        })}
      </ul>

      <ConfirmDialog
        open={confirmOpen}
        onOpenChange={setConfirmOpen}
        title={t('forkprobe.deleteHistoryTitle')}
        description={t('forkprobe.deleteHistoryConfirm', { n: selected.size })}
        variant="destructive"
        onConfirm={handleDelete}
      />
    </div>
  )
}

function HistoryDetail({
  item,
  detail,
  isLoading,
}: {
  item: ComparisonHistoryItem
  detail: ComparisonStatusResponse | undefined
  isLoading: boolean
}) {
  const { t, i18n } = useTranslation()
  if (isLoading) {
    return (
      <div className="flex items-center justify-center py-16">
        <Loader2 className="w-6 h-6 animate-spin text-primary" />
      </div>
    )
  }

  if (!detail) {
    return (
      <div className="flex flex-col items-center justify-center py-16 text-center">
        <p className="text-sm text-muted-foreground">{t('forkprobe.historyNotFound')}</p>
      </div>
    )
  }

  const isFailed = detail.status === 'FAILED'
  const isCancelled = detail.status === 'CANCELLED'

  return (
    <div className="space-y-4">
      {/* Task description + status */}
      <div className="flex items-start justify-between gap-3">
        <h3
          className="text-base font-semibold leading-snug"
          style={{ color: 'hsl(var(--foreground))' }}
        >
          {item.taskDescription}
        </h3>
        <span
          className={cn(
            'shrink-0 px-2 py-0.5 rounded-full text-xs font-medium',
            isFailed && 'bg-red-100 text-red-600',
            isCancelled && 'bg-gray-100 text-gray-500',
            !isFailed && !isCancelled && 'bg-emerald-100 text-emerald-700',
          )}
        >
          {STATUS_KEYS[detail.status] ? t(STATUS_KEYS[detail.status]) : detail.status}
        </span>
      </div>

      <div className="text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
        {t('forkprobe.skillCount', { n: detail.results.length })}
        {detail.completedAt && <span> · {formatTime(detail.completedAt, i18n.resolvedLanguage)}</span>}
      </div>

      {detail.error && (
        <div className="p-3 rounded-lg bg-red-50 border border-red-200 text-red-700 text-sm">
          {detail.error}
        </div>
      )}

      {detail.results.map((result, idx) => (
        <ComparisonResultCard key={result.skillCoordinate} result={result} index={idx} />
      ))}
    </div>
  )
}
