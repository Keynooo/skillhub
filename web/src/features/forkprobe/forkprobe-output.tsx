import { useMemo } from 'react'
import { MarkdownRenderer } from '@/features/skill/markdown-renderer'

type OutputPart =
  | { type: 'text'; value: string }
  | { type: 'svg'; value: string }

interface ForkprobeOutputProps {
  content: string
  className?: string
}

/**
 * Splits raw forkprobe output into Markdown text segments and fenced `svg`
 * blocks, so a skill that emits an SVG diagram (e.g. global/diagram-maker) is
 * shown as an actual image instead of a wall of `<rect>`/`<line>` source code.
 *
 * SVG is rendered via an `data:image/svg+xml` `<img>` rather than inlined
 * HTML: loading SVG as an image disables scripting and external resource
 * loads in the browser, so untrusted skill output stays safe without an
 * extended sanitizer allowlist.
 */
export function ForkprobeOutput({ content, className }: ForkprobeOutputProps) {
  const parts = useMemo(() => splitSvgFences(content), [content])

  return (
    <div className={className}>
      {parts.map((part, i) =>
        part.type === 'svg' ? (
          <img
            key={i}
            src={svgToDataUri(part.value)}
            alt="SVG diagram"
            className="my-4 h-auto max-w-full rounded-lg border border-border/60"
          />
        ) : (
          <MarkdownRenderer key={i} content={part.value} />
        )
      )}
    </div>
  )
}

function splitSvgFences(content: string): OutputPart[] {
  const fence = /```svg\s*\n([\s\S]*?)```/g
  const parts: OutputPart[] = []
  let last = 0
  let m: RegExpExecArray | null

  while ((m = fence.exec(content)) !== null) {
    if (m.index > last) {
      parts.push({ type: 'text', value: content.slice(last, m.index) })
    }
    parts.push({ type: 'svg', value: m[1].trim() })
    last = m.index + m[0].length
  }

  if (last < content.length) {
    parts.push({ type: 'text', value: content.slice(last) })
  }
  if (parts.length === 0) {
    parts.push({ type: 'text', value: content })
  }
  return parts
}

function svgToDataUri(svg: string): string {
  const bytes = new TextEncoder().encode(svg)
  let binary = ''
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return `data:image/svg+xml;base64,${btoa(binary)}`
}
