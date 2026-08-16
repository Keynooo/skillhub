'use client'

import { useState } from 'react'
import { X, History, Loader2, ChevronRight, ArrowLeft } from 'lucide-react'
import { cn } from '@/shared/lib/utils'
import {
  useForkprobeHistory,
  useForkprobeHistoryDetail,
} from './use-forkprobe-queries'
import { ComparisonResultCard } from './comparison-result-card'
import type { ComparisonHistoryItem, ComparisonStatusResponse } from './forkprobe-api'

const STATUS_LABELS: Record<string, string> = {
  COMPLETED: '已完成',
  FAILED: '失败',
  CANCELLED: '已取消',
}

function formatTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return d.toLocaleString('zh-CN', {
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
  const { data: history, isLoading } = useForkprobeHistory(20, open)
  const [selectedItem, setSelectedItem] = useState<ComparisonHistoryItem | null>(null)
  const { data: detail, isLoading: detailLoading } = useForkprobeHistoryDetail(
    open ? (selectedItem?.comparisonId ?? null) : null,
  )

  if (!open) return null

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 bg-black/30 z-40"
        onClick={onClose}
        aria-hidden="true"
      />

      {/* Modal */}
      <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
        <div
          className="w-full max-w-3xl max-h-[85vh] flex flex-col rounded-2xl border shadow-2xl"
          style={{ background: 'hsl(var(--card))', borderColor: 'hsl(var(--border))' }}
        >
          {/* Header */}
          <div className="flex items-center justify-between px-5 py-4 border-b shrink-0">
            <div className="flex items-center gap-2 min-w-0">
              {selectedItem && (
                <button
                  onClick={() => setSelectedItem(null)}
                  className="p-1.5 rounded-lg hover:bg-secondary transition-colors"
                  aria-label="返回列表"
                >
                  <ArrowLeft className="w-4 h-4" />
                </button>
              )}
              <History className="w-5 h-5 text-primary shrink-0" />
              <h2 className="text-lg font-semibold" style={{ color: 'hsl(var(--foreground))' }}>
                {selectedItem ? '历史详情' : '历史记录'}
              </h2>
            </div>
            <button
              onClick={onClose}
              className="p-2 rounded-lg hover:bg-secondary transition-colors"
              aria-label="关闭"
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
        </div>
      </div>
    </>
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
        <p className="text-sm text-muted-foreground mt-3">还没有对比记录</p>
      </div>
    )
  }

  return (
    <ul className="space-y-2">
      {history.map((item) => {
        const status = item.status
        const isFailed = status === 'FAILED'
        const isCancelled = status === 'CANCELLED'
        return (
          <li key={item.comparisonId}>
            <button
              type="button"
              onClick={() => onSelect(item)}
              className="w-full text-left px-4 py-3 rounded-xl border hover:bg-muted/50 transition-colors"
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
                      {STATUS_LABELS[status] ?? status}
                    </span>
                    <span>{item.skillCount} 个技能</span>
                    {item.provider && <span>· {item.provider}</span>}
                    <span>· {formatTime(item.createdAt)}</span>
                  </div>
                </div>
                <ChevronRight className="w-4 h-4 text-muted-foreground shrink-0" />
              </div>
            </button>
          </li>
        )
      })}
    </ul>
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
        <p className="text-sm text-muted-foreground">未找到该记录</p>
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
          {STATUS_LABELS[detail.status] ?? detail.status}
        </span>
      </div>

      <div className="text-xs" style={{ color: 'hsl(var(--muted-foreground))' }}>
        {detail.results.length} 个技能
        {detail.completedAt && <span> · {formatTime(detail.completedAt)}</span>}
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
