import { useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { useJobs, useStartJob } from '../api/hooks'
import type { WorkTextPart, WorkTextPartKey } from '../api/types'
import { fmtDateTime } from '../lib/format'
import { Icon } from './Icon'

const SOURCE_LABEL = { draft: '초안 (할 일·메모로 자동)', ai: 'AI 작성', manual: '직접 수정함' } as const
const HINT: Record<string, string> = {
  full: '이번 주 한 일 전체 — 그 주에 작업했거나 완료한 할 일만 들어가요.',
}

/** 주간 업무 내용 창(예전 진입점) — 편집기를 띄운다 */
export function WorkTextDialog({ week, onClose }: { week: string; onClose: () => void }) {
  return (
    <div className="dlg-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <WorkTextEditor key={week} week={week} onClose={onClose} />
    </div>
  )
}

/** 주간 업무 내용 — 전체 + 설정에서 만든 요약 갈래 탭. 탭마다 고치면 자동 저장, 복사. 주가 바뀌면 key 로 새로 그린다 */
function WorkTextEditor({ week, onClose }: { week: string; onClose: () => void }) {
  const qc = useQueryClient()
  const { data, isLoading } = useQuery({ queryKey: ['worktext', week], queryFn: () => api.workText(week), staleTime: 0 })
  const { data: jobs } = useJobs()
  const ai = useStartJob(({ part }: { part: WorkTextPartKey | 'all' }) => api.workTextAi(week, part))
  const [tab, setTab] = useState<WorkTextPartKey>('full')
  const [texts, setTexts] = useState<Partial<Record<WorkTextPartKey, string>>>({})
  const [state, setState] = useState<'idle' | 'dirty' | 'saved' | 'copied'>('idle')
  const dirty = useRef<Set<WorkTextPartKey>>(new Set())
  const timer = useRef<number | undefined>(undefined)

  const running = jobs?.find((j) => j.key.startsWith(`worktext:${week}:`) && (j.status === 'QUEUED' || j.status === 'RUNNING'))
  const failed = jobs?.find((j) => j.key.startsWith(`worktext:${week}:`) && j.status === 'FAILED')
  const part: WorkTextPart | undefined = data?.parts.find((p) => p.part === tab)

  // 서버 글(초안·AI 결과)이 바뀌면 받아온다 — 고치는 중인 탭은 덮지 않는다
  useEffect(() => {
    if (!data) return
    setTexts((prev) => {
      const next = { ...prev }
      for (const p of data.parts) if (!dirty.current.has(p.part)) next[p.part] = p.content
      return next
    })
  }, [data])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      window.clearTimeout(timer.current)
    }
  }, [onClose])

  const save = async (p: WorkTextPartKey, v: string) => {
    await api.saveWorkText(week, p, v)
    dirty.current.delete(p)
    setState('saved')
    qc.invalidateQueries({ queryKey: ['worktext', week] })
  }

  const onChange = (v: string) => {
    const p = tab
    setTexts((prev) => ({ ...prev, [p]: v }))
    dirty.current.add(p)
    setState('dirty')
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => save(p, v), 1000)
  }

  // 서식(HTML: 굵게·글머리표 들여쓰기)과 평문(** 뺌)을 같이 넣는다 — 붙여 넣는 곳이 고른다
  const copy = async () => {
    const text = texts[tab] ?? ''
    const plain = stripBold(text)
    try {
      await navigator.clipboard.write([
        new ClipboardItem({
          'text/html': new Blob([toHtml(text)], { type: 'text/html' }),
          'text/plain': new Blob([plain], { type: 'text/plain' }),
        }),
      ])
    } catch {
      await navigator.clipboard.writeText(plain)
    }
    setState('copied')
    window.setTimeout(() => setState('idle'), 1500)
  }

  const reset = async () => {
    if (!window.confirm(`직접 고친 '${part?.title}'을 지우고 초안으로 되돌릴까요?`)) return
    window.clearTimeout(timer.current)
    dirty.current.delete(tab)
    await api.resetWorkText(week, tab)
    qc.invalidateQueries({ queryKey: ['worktext', week] })
  }

  return (
    <div className="dlg" role="dialog" aria-label="업무 내용">
      <div className="dlg-head">
        <div>
          <h2>{data?.label ?? week} 업무 내용</h2>
          <p>
            {part && <span className={`dlg-src ${part.source}`}>{SOURCE_LABEL[part.source]}</span>}
            {part?.generatedAt && <span className="subtle"> · {fmtDateTime(part.generatedAt)}</span>}
            {state === 'dirty' && <span className="subtle"> · 입력 중…</span>}
            {state === 'saved' && <span className="subtle"> · 저장됨</span>}
          </p>
        </div>
        <button className="btn btn-ghost icon-btn btn-sm" onClick={onClose} aria-label="닫기">
          <Icon name="x" />
        </button>
      </div>
      <div className="dlg-tabs" role="tablist">
        {(data?.parts ?? []).map((p) => (
          <button
            key={p.part}
            role="tab"
            aria-selected={tab === p.part}
            className={tab === p.part ? 'on' : ''}
            onClick={() => setTab(p.part)}
          >
            {p.title}
            {p.source !== 'draft' && <span className={`dlg-dot ${p.source}`} title={SOURCE_LABEL[p.source]} />}
          </button>
        ))}
      </div>
      <div className="dlg-tools">
        <button className="btn btn-primary btn-sm" onClick={() => ai.mutate({ part: tab })} disabled={!!running || ai.isPending}>
          {running ? (
            <div className="spinner" style={{ borderColor: 'rgba(255,255,255,.35)', borderTopColor: '#fff' }} />
          ) : (
            <Icon name="sparkle" size={13} />
          )}
          {running ? (running.message ?? 'AI 작성 중') : 'AI로 다듬기'}
        </button>
        <button
          className="btn btn-sm"
          onClick={() => ai.mutate({ part: 'all' })}
          disabled={!!running || ai.isPending}
          title="모든 탭을 한 번에 AI 로 — 직접 고친 탭은 그대로 둬요"
        >
          모두 AI로
        </button>
        <button className="btn btn-sm" onClick={copy} disabled={!texts[tab]}>
          <Icon name={state === 'copied' ? 'check' : 'copy'} size={13} /> {state === 'copied' ? '복사됨' : '복사'}
        </button>
        {part && part.source !== 'draft' && (
          <button className="btn btn-ghost btn-sm" onClick={reset}>
            초안으로
          </button>
        )}
        <span className="dlg-hint">
          {HINT[tab] ?? '설정 > 주간보고 갈래에서 정한 요약이에요.'} 고치면 자동 저장되고 다음 주 AI 작성 때 문체 예시로 쓰여요. **두 별표** 사이는
          복사하면 굵게 붙어요.
        </span>
      </div>
      {failed && !running && (
        <div className="error-box" style={{ margin: '0 18px 10px' }}>
          AI 작성 실패: {failed.message}
        </div>
      )}
      {isLoading ? (
        <div className="empty">
          <div className="spinner" style={{ margin: '0 auto' }} />
        </div>
      ) : (
        <textarea className="dlg-text" value={texts[tab] ?? ''} onChange={(e) => onChange(e.target.value)} spellCheck={false} />
      )}
    </div>
  )
}

/** 굵게 표시(**) 를 뺀 평문 */
function stripBold(text: string): string {
  return text.replace(/\*\*(.+?)\*\*/g, '$1')
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
const inline = (s: string) => esc(s).replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')

/**
 * 글 → HTML. "- " 줄은 공백 4칸 들여쓰기 단계대로 겹친 글머리표(ul), 나머지 줄은 문단.
 * 노션·워드·메일에 붙이면 글머리표 구조와 굵게가 그대로 간다
 */
function toHtml(text: string): string {
  const out: string[] = []
  let depth = 0 // 열린 ul 수
  const closeTo = (d: number) => {
    while (depth > d) {
      out.push('</li></ul>')
      depth--
    }
  }
  const openItems: boolean[] = [] // 단계별로 li 가 열려 있나
  for (const raw of text.split('\n')) {
    const m = raw.match(/^( *)- (.*)$/)
    if (!m) {
      closeTo(0)
      openItems.length = 0
      if (raw.trim()) out.push(`<p>${inline(raw.trim())}</p>`)
      continue
    }
    const level = Math.floor(m[1].length / 4) + 1
    if (level > depth) {
      while (depth < level) {
        out.push('<ul>')
        depth++
        openItems[depth] = false
      }
    } else {
      closeTo(level)
      if (openItems[level]) out.push('</li>')
    }
    out.push(`<li>${inline(m[2])}`)
    openItems[level] = true
  }
  closeTo(0)
  return out.join('')
}
