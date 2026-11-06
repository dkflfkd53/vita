import { useLayoutEffect, useRef, useState } from 'react'
import { projectColor, tint } from '../lib/format'
import type { TaskStatus } from '../api/types'

export function ProjectLabel({ project }: { project: string | null | undefined }) {
  if (!project) return null
  return (
    <span className="label" style={tint(projectColor(project))}>
      {project}
    </span>
  )
}

type DotVariant = 'busy' | 'live' | 'recent' | 'idle'

const DOT_TITLE: Record<DotVariant, string> = {
  busy: 'Claude 가 작업 중',
  live: '세션 열려 있음(대기)',
  recent: '최근 3일 내 활동',
  idle: '쉬는 중',
}

export function dotVariant(status: TaskStatus, busy: boolean): DotVariant {
  return status === 'live' ? (busy ? 'busy' : 'live') : status
}

export function StatusDot({ variant }: { variant: DotVariant }) {
  return <span className={`dot ${variant}`} title={DOT_TITLE[variant]} />
}

export function StatusLabel({ status, busy }: { status: TaskStatus; busy: boolean }) {
  if (status === 'live') {
    return (
      <span className="label" style={tint('var(--c-green)')}>
        {busy ? '작업 중' : '열려 있음'}
      </span>
    )
  }
  if (status === 'recent') {
    return (
      <span className="label" style={tint('var(--c-orange)')}>
        최근
      </span>
    )
  }
  return (
    <span className="label" style={{ color: 'var(--c-gray)', borderColor: 'var(--border)' }}>
      쉬는 중
    </span>
  )
}

export function Loading() {
  return (
    <div className="empty">
      <div className="spinner" style={{ margin: '0 auto' }} />
    </div>
  )
}

export function ErrorBox({ error }: { error: unknown }) {
  return <div className="error-box">{error instanceof Error ? error.message : String(error)}</div>
}

/** 어느 AI 도구의 세션인가 — Claude 만이면 표시하지 않는다(기본) */
export function ToolBadge({ tools }: { tools: string[] | undefined }) {
  if (!tools || !tools.includes('codex')) return null
  return <span className="tool-badge">{tools.includes('claude') ? 'Claude·Codex' : 'Codex'}</span>
}

/**
 * 요청 원문 — 4줄까지 보이고, 넘치면 '펼치기'로 전문·'접기'로 다시 4줄.
 * 수집할 때 너무 긴 원문은 앞부분만 저장돼(…로 끝남) 그때는 그렇다고 알린다
 */
export function PromptText({ text }: { text: string }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [open, setOpen] = useState(false)
  const [overflow, setOverflow] = useState(false)
  useLayoutEffect(() => {
    const el = ref.current
    if (el && !open) setOverflow(el.scrollHeight > el.clientHeight + 1)
  }, [text, open])
  const cut = text.length > 2000 && text.endsWith('…')
  return (
    <span className="prompt-text">
      <span ref={ref} className={`txt${open ? ' full' : ''}`}>
        {text}
      </span>
      {(overflow || open) && (
        <button type="button" className="prompt-toggle" onClick={() => setOpen(!open)} aria-expanded={open}>
          {open ? '접기' : '펼치기'}
        </button>
      )}
      {open && cut && <span className="prompt-cut">너무 길어 수집할 때 앞부분만 저장된 요청이에요</span>}
    </span>
  )
}
