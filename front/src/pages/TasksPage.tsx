import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useHiddenSessions, useOverview } from '../api/hooks'
import { api } from '../api/client'
import type { TaskStatus, TaskSummary, TaskWork, WorkStatus } from '../api/types'
import { Icon } from '../components/Icon'
import { StatusIcon } from '../components/workFields'
import { ErrorBox, Loading, ProjectLabel, StatusDot, ToolBadge, dotVariant } from '../components/common'
import { fmtDateTime, fmtRelative, fmtShort, fmtUsd } from '../lib/format'

type Filter = 'active' | 'all' | 'idle'

const FILTERS: { key: Filter; label: string; match: (s: TaskStatus) => boolean }[] = [
  { key: 'active', label: '진행 중', match: (s) => s !== 'idle' },
  { key: 'idle', label: '쉬는 중', match: (s) => s === 'idle' },
  { key: 'all', label: '전체', match: () => true },
]

/** embedded = 세션 화면의 "목록" 보기로 들어감(머리말 없이) */
export function TasksPage({ embedded }: { embedded?: boolean } = {}) {
  const { data, isLoading, error } = useOverview()
  const [filter, setFilter] = useState<Filter>('active')
  const [q, setQ] = useState('')
  const [grouping, setGrouping] = useState<Grouping>(readGrouping)
  const taskWork = useQuery({ queryKey: ['task-work'], queryFn: () => api.taskWork(), refetchInterval: 60_000 })
  const tasks = data?.tasks ?? []

  const counts = useMemo(() => Object.fromEntries(FILTERS.map((f) => [f.key, tasks.filter((t) => f.match(t.status)).length])), [tasks])
  const shown = useMemo(() => {
    const f = FILTERS.find((x) => x.key === filter)!
    const needle = q.trim().toLowerCase()
    return tasks.filter(
      (t) =>
        f.match(t.status) &&
        (!needle ||
          t.title.toLowerCase().includes(needle) ||
          t.projects.some((p) => p.toLowerCase().includes(needle)) ||
          (t.lastSummary ?? '').toLowerCase().includes(needle)),
    )
  }, [tasks, filter, q])

  const body = (
    <>
      <div className="list-toolbar">
        <div className="search-wrap">
          <Icon name="search" size={14} />
          <input className="search" placeholder="작업·프로젝트·요약 검색" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="segmented" title="세션을 할 일 기준으로 묶어 보기">
          {GROUPINGS.map((g) => (
            <button
              key={g.key}
              className={grouping === g.key ? 'on' : ''}
              onClick={() => {
                setGrouping(g.key)
                try {
                  localStorage.setItem(GROUPING_KEY, g.key)
                } catch {
                  // 기억 못 해도 이번엔 바뀐다
                }
              }}
            >
              {g.label}
            </button>
          ))}
        </div>
      </div>

      {error && <ErrorBox error={error} />}
      <div className="list">
        <div className="list-head">
          {FILTERS.map((f) => (
            <button key={f.key} className={filter === f.key ? 'on' : ''} onClick={() => setFilter(f.key)}>
              {f.label} <span className="num">{counts[f.key] ?? 0}</span>
            </button>
          ))}
        </div>
        {isLoading && <Loading />}
        {!isLoading && shown.length === 0 && (
          <div className="empty">
            <h3>해당하는 작업이 없어요</h3>
          </div>
        )}
        {grouping === 'none' && shown.map((t) => <TaskRow key={t.key} t={t} />)}
        {grouping === 'item' &&
          groupByItem(shown, taskWork.data ?? []).map((g) => (
            <div key={g.key} className="tg">
              <div className="tg-head">
                {g.status ? (
                  <StatusIcon status={g.status} size={14} />
                ) : (
                  <span className={`tg-dot${g.kind === 'routine' ? ' routine' : ''}`} />
                )}
                {g.itemId ? (
                  <Link to={`/work/${g.itemId}`} className="tg-title">
                    {g.title}
                  </Link>
                ) : (
                  <span className="tg-title muted">{g.title}</span>
                )}
                {g.category && <span className="tg-cat">{g.category}</span>}
                <span className="tg-sum num">
                  세션 {g.tasks.length} · {fmtShort(g.tasks.reduce((s, t) => s + t.activeSec, 0))}
                </span>
              </div>
              {g.tasks.map((t) => (
                <TaskRow key={t.key} t={t} />
              ))}
            </div>
          ))}
      </div>

      <HiddenSessions />
    </>
  )
  if (embedded) return body
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>세션</h1>
          <p>Claude Code·Codex 세션을 제목 기준으로 묶었어요. 이어진 세션과 제목이 같은 세션은 하나로 합쳐져요.</p>
        </div>
      </div>
      {body}
    </div>
  )
}

function TaskRow({ t }: { t: TaskSummary }) {
  return (
    <div className="list-row">
      <StatusDot variant={dotVariant(t.status, t.busy)} />
      <div style={{ minWidth: 0 }}>
        <div className="title">
          <Link to={`/sessions/${t.key}`}>{t.title}</Link>
          {t.projects.slice(0, 2).map((p) => (
            <ProjectLabel key={p} project={p} />
          ))}
          <ToolBadge tools={t.tools} />
          {t.sessionCount > 1 && (
            <span className="subtle" style={{ fontSize: 12, fontWeight: 400 }}>
              세션 {t.sessionCount}
            </span>
          )}
        </div>
        <div className="sub">{t.lastSummary ?? `요청 ${t.prompts}건 · 수정 ${t.edits}회`}</div>
      </div>
      <div className="col">
        <b>{fmtShort(t.activeSec)}</b>
        <div className="subtle">작업 시간</div>
      </div>
      <div className="col hide-sm">
        <b>{t.activeDays}일</b>
        <div className="subtle">활동일</div>
      </div>
      <div className="col hide-sm" title="토큰 사용량 API 환산">
        <b>{fmtUsd(t.costUsd)}</b>
        <div className="subtle">사용량</div>
      </div>
      <div className="col hide-sm" title={fmtDateTime(t.lastAt)}>
        {fmtRelative(t.lastAt)}
      </div>
    </div>
  )
}

type Grouping = 'item' | 'none'
const GROUPING_KEY = 'worklog-sessions-grouping'
const GROUPINGS: { key: Grouping; label: string }[] = [
  { key: 'item', label: '할 일별' },
  { key: 'none', label: '묶지 않기' },
]

function readGrouping(): Grouping {
  try {
    return localStorage.getItem(GROUPING_KEY) === 'none' ? 'none' : 'item'
  } catch {
    return 'item'
  }
}

interface TaskGroup {
  key: string
  kind: 'item' | 'loose' | 'routine'
  itemId: number | null
  title: string
  status: WorkStatus | null
  category: string | null
  lastAt: string
  tasks: TaskSummary[]
}

const STATUS_RANK: Record<WorkStatus, number> = { IN_PROGRESS: 0, PLANNED: 1, ON_HOLD: 2, DONE: 3 }

/** 작업마다 가장 최근에 연결된 할 일 아래로. 진행 중 할 일 먼저, 그다음 최근 작업 순. 할 일 없는 작업·배포는 맨 뒤 */
function groupByItem(tasks: TaskSummary[], work: TaskWork[]): TaskGroup[] {
  const byTask = new Map(work.map((w) => [w.taskKey, w]))
  const groups = new Map<string, TaskGroup>()
  for (const t of tasks) {
    const w = byTask.get(t.key)
    const kind = w?.routine ? 'routine' : w?.itemId ? 'item' : 'loose'
    const key = kind === 'item' ? `item-${w!.itemId}` : kind
    let g = groups.get(key)
    if (!g) {
      g = {
        key,
        kind,
        itemId: kind === 'item' ? w!.itemId : null,
        title: kind === 'item' ? (w!.itemTitle ?? '(할 일)') : kind === 'routine' ? '반복 업무 (배포)' : '할 일에 안 붙은 세션',
        status: kind === 'item' ? w!.itemStatus : null,
        category: kind === 'item' ? w!.itemCategory : null,
        lastAt: '',
        tasks: [],
      }
      groups.set(key, g)
    }
    g.tasks.push(t)
    if ((t.lastAt ?? '') > g.lastAt) g.lastAt = t.lastAt ?? ''
  }
  const tail = { item: 0, loose: 1, routine: 2 }
  return [...groups.values()].sort(
    (a, b) =>
      tail[a.kind] - tail[b.kind] ||
      (a.status && b.status ? STATUS_RANK[a.status] - STATUS_RANK[b.status] : 0) ||
      b.lastAt.localeCompare(a.lastAt),
  )
}

function HiddenSessions() {
  const { data } = useHiddenSessions()
  const qc = useQueryClient()
  const unhide = useMutation({
    mutationFn: (id: string) => api.updateSession(id, { hidden: false }),
    onSuccess: () => qc.invalidateQueries(),
  })
  if (!data || data.length === 0) return null
  return (
    <details className="card" style={{ marginTop: 20 }}>
      <summary className="card-head" style={{ paddingBottom: 14 }}>
        <h3>
          <Icon name="eyeOff" size={13} /> 숨긴 세션 {data.length}
        </h3>
      </summary>
      <div className="card-body" style={{ paddingTop: 0 }}>
        {data.map((s) => (
          <div key={s.id} className="session-item">
            <div className="row">
              <span className="name">{s.title}</span>
              <button className="btn btn-sm" onClick={() => unhide.mutate(s.id)}>
                <Icon name="eye" size={12} /> 다시 보이기
              </button>
            </div>
            <div className="meta">
              {s.project ?? '-'} · {fmtDateTime(s.lastAt)}
            </div>
          </div>
        ))}
      </div>
    </details>
  )
}
