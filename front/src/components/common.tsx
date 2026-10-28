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
