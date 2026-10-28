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

  const copy = async () => {
    await navigator.clipboard.writeText(texts[tab] ?? '')
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
        <span className="dlg-hint">{HINT[tab] ?? '설정 > 주간보고 갈래에서 정한 요약이에요.'} 고치면 자동 저장되고 다음 주 AI 작성 때 문체 예시로 쓰여요.</span>
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
