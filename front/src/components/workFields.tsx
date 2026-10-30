import { useEffect, useRef, useState, type KeyboardEvent } from 'react'
import type { LinkSource, WorkItem, WorkItemPatch, WorkPriority, WorkStatus } from '../api/types'
import { parseYmd, tint, today, weekday } from '../lib/format'

// Notion 데이터베이스처럼 셀을 눌러 바로 고친다. 바뀐 값만 onSave 로 PATCH

export const STATUS: Record<WorkStatus, { label: string; color: string }> = {
  PLANNED: { label: '예정', color: 'var(--c-gray)' },
  IN_PROGRESS: { label: '진행 중', color: 'var(--c-blue)' },
  DONE: { label: '완료', color: 'var(--c-green)' },
  ON_HOLD: { label: '보류', color: 'var(--c-yellow)' },
}

export const PRIORITY: Record<WorkPriority, { label: string; mark: string; color: string }> = {
  HIGH: { label: '높음', mark: '↑', color: 'var(--accent)' },
  MEDIUM: { label: '보통', mark: '–', color: 'var(--c-yellow)' },
  LOW: { label: '낮음', mark: '↓', color: 'var(--c-gray)' },
}

export const SOURCE_LABEL: Record<Exclude<LinkSource, null>, string> = {
  manual: '직접',
  pin: '고정',
  auto: 'AI',
  inherit: '이어받음',
}

type Save = (patch: WorkItemPatch) => void

export function StatusSelect({ value, onSave }: { value: WorkStatus; onSave: Save }) {
  const s = STATUS[value]
  return (
    <select
      className="pill-select"
      value={value}
      onChange={(e) => onSave({ status: e.target.value as WorkStatus })}
      style={tint(s.color, 33, 10)}
    >
      {Object.entries(STATUS).map(([k, v]) => (
        <option key={k} value={k}>
          {v.label}
        </option>
      ))}
    </select>
  )
}

export function PrioritySelect({ value, onSave }: { value: WorkPriority; onSave: Save }) {
  const p = PRIORITY[value]
  return (
    <select className="cell-select" value={value} onChange={(e) => onSave({ priority: e.target.value as WorkPriority })} style={{ color: p.color }}>
      {Object.entries(PRIORITY).map(([k, v]) => (
        <option key={k} value={k}>
          {v.mark} {v.label}
        </option>
      ))}
    </select>
  )
}

const NEW = '__new__'

export function CategorySelect({ value, categories, onSave }: { value: string | null; categories: string[]; onSave: Save }) {
  const [adding, setAdding] = useState(false)
  const [text, setText] = useState('')
  if (adding) {
    const commit = () => {
      setAdding(false)
      if (text.trim()) onSave({ category: text.trim() })
      setText('')
    }
    return (
      <input
        className="cell-input"
        autoFocus
        placeholder="새 카테고리"
        value={text}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') commit()
          if (e.key === 'Escape') setAdding(false)
        }}
      />
    )
  }
  return (
    <select
      className="cell-select"
      value={value ?? ''}
      onChange={(e) => (e.target.value === NEW ? setAdding(true) : onSave({ category: e.target.value }))}
    >
      <option value="">—</option>
      {categories.map((c) => (
        <option key={c} value={c}>
          {c}
        </option>
      ))}
      <option value={NEW}>+ 새 카테고리…</option>
    </select>
  )
}

/** 포커스를 잃거나 Enter 일 때 저장, Esc 는 되돌림 */
/** 세부 분류 — 카테고리에 세부 분류가 있을 때만 쓴다 */
export function SubtypeSelect({ value, subtypes, onSave }: { value: string | null; subtypes: string[]; onSave: Save }) {
  return (
    <select className="cell-select" value={value ?? ''} onChange={(e) => onSave({ subtype: e.target.value })}>
      <option value="">미분류</option>
      {subtypes.map((s) => (
        <option key={s} value={s}>
          {s}
        </option>
      ))}
    </select>
  )
}

export function TextCell({
  value,
  placeholder,
  onCommit,
  className = 'cell-input',
  required,
}: {
  value: string | null
  placeholder?: string
  onCommit: (v: string) => void
  className?: string
  required?: boolean
}) {
  const [text, setText] = useState(value ?? '')
  useEffect(() => setText(value ?? ''), [value])
  const commit = () => {
    const v = text.trim()
    if (v === (value ?? '')) return
    if (required && !v) {
      setText(value ?? '')
      return
    }
    onCommit(v)
  }
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
    if (e.key === 'Escape') {
      setText(value ?? '')
      setTimeout(() => (e.target as HTMLInputElement).blur())
    }
  }
  return (
    <input
      className={className}
      value={text}
      placeholder={placeholder}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={onKey}
    />
  )
}

export function dDay(due: string | null, status: WorkStatus): { text: string; tone: 'over' | 'soon' | 'ok' } | null {
  if (!due || status === 'DONE') return null
  const diff = Math.round((parseYmd(due).getTime() - parseYmd(today()).getTime()) / 86400000)
  if (diff < 0) return { text: `D+${-diff}`, tone: 'over' }
  if (diff === 0) return { text: 'D-day', tone: 'soon' }
  return { text: `D-${diff}`, tone: diff <= 2 ? 'soon' : 'ok' }
}

/** 브라우저 기본 날짜칸("2026. 10. 24." / "연도. 월. 일.") 대신 짧은 글자로 보여주고, 누르면 달력을 띄운다 */
export function DueDateInput({ value, status, onSave }: { value: string | null; status: WorkStatus; onSave: Save }) {
  const ref = useRef<HTMLInputElement>(null)
  const d = dDay(value, status)
  const open = () => {
    const input = ref.current
    if (!input) return
    try {
      input.showPicker()
    } catch {
      input.focus()
    }
  }
  return (
    <div className="due-cell">
      <button type="button" className={`due-btn${value ? '' : ' empty'}`} onClick={open}>
        {value ? `${parseYmd(value).getMonth() + 1}/${parseYmd(value).getDate()} (${weekday(value)})` : '—'}
      </button>
      {d && <span className={`dday ${d.tone}`}>{d.text}</span>}
      {value && (
        <button type="button" className="due-clear" onClick={() => onSave({ dueDate: '' })} aria-label="마감일 지우기">
          ×
        </button>
      )}
      <input ref={ref} type="date" className="due-native" value={value ?? ''} onChange={(e) => onSave({ dueDate: e.target.value })} tabIndex={-1} />
    </div>
  )
}


export function itemPatchSaver(item: WorkItem, save: (id: number, patch: WorkItemPatch) => void): Save {
  return (patch) => save(item.id, patch)
}

/**
 * 노션 본문처럼 테두리 없는 여러 줄 메모. 입력을 멈추고 1초 뒤, 또는 포커스를 잃을 때 저장한다.
 * 높이는 내용에 맞춰 늘어난다.
 */
export function MemoEditor({ value, onSave, placeholder = '메모 — 세부 할 일, 진행 상황, 참고 링크…' }: {
  value: string | null
  onSave: (memo: string) => void
  placeholder?: string
}) {
  const [text, setText] = useState(value ?? '')
  const [state, setState] = useState<'idle' | 'dirty' | 'saved'>('idle')
  const ref = useRef<HTMLTextAreaElement>(null)
  const timer = useRef<number | undefined>(undefined)
  const saved = useRef(value ?? '')

  useEffect(() => {
    // 다른 곳에서 바뀐 값은 편집 중이 아닐 때만 받아온다
    if (document.activeElement !== ref.current) {
      setText(value ?? '')
      saved.current = value ?? ''
    }
  }, [value])

  useEffect(() => {
    const el = ref.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight}px`
  }, [text])

  useEffect(() => () => window.clearTimeout(timer.current), [])

  const flush = (v: string) => {
    window.clearTimeout(timer.current)
    if (v === saved.current) return
    saved.current = v
    onSave(v)
    setState('saved')
    window.setTimeout(() => setState((s) => (s === 'saved' ? 'idle' : s)), 1500)
  }

  return (
    <div className="memo">
      <textarea
        ref={ref}
        rows={1}
        value={text}
        placeholder={placeholder}
        onChange={(e) => {
          const v = e.target.value
          setText(v)
          setState('dirty')
          window.clearTimeout(timer.current)
          timer.current = window.setTimeout(() => flush(v), 1000)
        }}
        onBlur={() => flush(text)}
      />
      {state !== 'idle' && <span className="memo-state">{state === 'dirty' ? '입력 중…' : '저장됨'}</span>}
    </div>
  )
}

/** 상태 아이콘(Linear 풍) — ○ 예정, ◐ 진행 중, ⏸ 보류, ✓ 완료 */
export function StatusIcon({ status, size = 16 }: { status: WorkStatus; size?: number }) {
  const c = STATUS[status].color
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" aria-hidden style={{ flex: 'none' }}>
      {status === 'DONE' ? (
        <>
          <circle cx="8" cy="8" r="7" fill={c} />
          <path d="M4.8 8.2l2.1 2.1 4.3-4.4" fill="none" stroke="#fff" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
        </>
      ) : (
        <>
          <circle cx="8" cy="8" r="6.2" fill="none" stroke={c} strokeWidth="1.6" />
          {status === 'IN_PROGRESS' && <path d="M8 3.6a4.4 4.4 0 0 1 0 8.8z" fill={c} />}
          {status === 'ON_HOLD' && (
            <>
              <rect x="5.9" y="5.3" width="1.5" height="5.4" rx="0.6" fill={c} />
              <rect x="8.6" y="5.3" width="1.5" height="5.4" rx="0.6" fill={c} />
            </>
          )}
        </>
      )}
    </svg>
  )
}
