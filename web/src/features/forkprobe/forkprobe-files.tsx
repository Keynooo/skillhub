import { Download, FileText } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import type { ForkprobeOutputFile } from './forkprobe-api'

/**
 * Renders the deliverable files a sandbox run wrote to `/output` as a list of
 * download links. Shared by the workbench central panel and the comparison
 * result/history cards so the behaviour stays identical.
 */
export function ForkprobeFileList({ files }: { files?: ForkprobeOutputFile[] }) {
  const { t } = useTranslation()

  if (!files || files.length === 0) return null

  return (
    <div className="space-y-2">
      <p className="text-xs font-medium" style={{ color: 'hsl(var(--muted-foreground))' }}>
        {t('forkprobe.deliverableFiles')}
      </p>
      <div className="flex flex-col gap-2">
        {files.map((file) => (
          <div
            key={file.name}
            className="flex items-center justify-between gap-2 rounded-lg border border-border/60 bg-card px-3 py-2"
          >
            <div className="flex items-center gap-2 min-w-0">
              <FileText className="w-4 h-4 shrink-0 text-muted-foreground" />
              <span className="text-sm truncate" style={{ color: 'hsl(var(--foreground))' }}>
                {file.name}
              </span>
              <span className="text-xs shrink-0" style={{ color: 'hsl(var(--muted-foreground))' }}>
                {formatBytes(file.sizeBytes)}
              </span>
            </div>
            <button
              type="button"
              onClick={() => downloadFile(file)}
              className="inline-flex items-center gap-1 text-xs font-medium text-primary hover:underline shrink-0"
            >
              <Download className="w-3.5 h-3.5" />
              {t('forkprobe.downloadFile')}
            </button>
          </div>
        ))}
      </div>
    </div>
  )
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function downloadFile(file: ForkprobeOutputFile): void {
  try {
    // Backend base64 is standard (Java Base64.getEncoder()), so `atob` decodes it directly.
    const bytes = Uint8Array.from(atob(file.contentBase64), (c) => c.charCodeAt(0))
    const blob = new Blob([bytes], { type: file.contentType || 'application/octet-stream' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = file.name
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
  } catch (e) {
    console.error('Failed to download sandbox file', file.name, e)
  }
}
