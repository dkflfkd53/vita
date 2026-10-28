import { useEffect, useRef, useState, type MouseEvent as ReactMouseEvent } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useBoard, useRunningJob, useStartJob, useUpdateItem, useWorkMutation } from '../api/hooks'
import { api } from '../api/client'
import type { Board, BoardKind, UnassignedSession, WorkItem, WorkItemPatch, WorkPriority, WorkStatus } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel } from '../components/common'
import { UsageButton } from '../components/UsageBreakdown'
import { CategorySelect, DueDateInput, MemoEditor, PRIORITY, PrioritySelect, STATUS, StatusIcon, TextCell, dDay } from '../components/workFields'
import { MonthOverview } from '../components/MonthOverview'
import { WorkTextDialog } from '../components/WorkTextDialog'
import { addDays, fmtShort, fmtUsd, monthKey, monthWeek, parseYmd, tint, weekKey, weekLabel, weekday, ymd } from '../lib/format'

// 디자인 참고: 줄 밀도·상태 아이콘·우선순위 막대는 Linear, 보조 줄(세션·메모 미리보기)과 클릭하면 펼쳐지는 카드는 Things 3

const NO_CATEGORY = '(카테고리 없음)'
const VIEW_KEY = 'worklog-work-view'

function readView(): BoardKind {
  try {
    return localStorage.getItem(VIEW_KEY) === 'month' ? 'month' : 'week'
  } catch {
    return 'week'
  }
}

const STATUS_ORDER: WorkStatus[] = ['PLANNED', 'IN_PROGRESS', 'ON_HOLD', 'DONE']

function fmtDay(day: string) {
  const d = parseYmd(day)
  return `${d.getMonth() + 1}/${d.getDate()} ${weekday(day)}`
}

function rangeLabel(from: string, to: string) {
  const a = parseYmd(from)
  const b = parseYmd(to)
  return a.getMonth() === b.getMonth()
    ? `${a.getMonth() + 1}월 ${a.getDate()}일 – ${b.getDate()}일`
    : `${a.getMonth() + 1}월 ${a.getDate()}일 – ${b.getMonth() + 1}월 ${b.getDate()}일`
}

/** 버튼·입력칸·링크를 누른 건 줄 클릭(펼치기)으로 치지 않는다 */
function isControl(e: ReactMouseEvent) {
  return !!(e.target as HTMLElement).closest('button, a, input, select, textarea, label, .menu, .picker-pop')
}

export function WeekBoardPage() {
  const [params, setParams] = useSearchParams()
  const [savedView, setSavedView] = useState<BoardKind>(readView)
  const [textOpen, setTextOpen] = useState(false)
  const thisWeek = weekKey(new Date())
  const thisMonth = monthKey(new Date())
  const view: BoardKind = params.get('month') ? 'month' : params.get('week') ? 'week' : savedView
  const current = view === 'week' ? thisWeek : thisMonth
  const key = params.get(view) ?? current
  const { data, isLoading, error } = useBoard(view, key)
  const [openId, setOpenId] = useState<number | null>(null)
  const go = (k: string, v: BoardKind = view) => {
    setOpenId(null)
    setParams(k === (v === 'week' ? thisWeek : thisMonth) ? {} : { [v]: k }, { replace: true })
  }
  const switchView = (v: BoardKind) => {
    setSavedView(v)
    try {
      localStorage.setItem(VIEW_KEY, v)
    } catch {
      // 기억 못 해도 지금은 바뀐다
    }
    go(v === 'week' ? thisWeek : thisMonth, v)
  }

  useEffect(() => {
    if (openId === null) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpenId(null)
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [openId])

  return (
    <div className="page todo-page">
      <header className="board-head">
        <div>
          <div className="board-eyebrow">
            {view === 'week' ? weekLabel(key) : `월간${data ? ` · ${data.weeks.length}주` : ''}`}
            {key === current && <span className="board-now">{view === 'week' ? '이번 주' : '이번 달'}</span>}
          </div>
          <h1 className="todo-title">{data ? (view === 'week' ? rangeLabel(data.from, ymd(addDays(parseYmd(data.from), 4))) : data.label) : key}</h1>
        </div>
        <div className="board-nav">
          <div className="segmented">
            <button className={view === 'week' ? 'on' : ''} onClick={() => switchView('week')}>
              주
            </button>
            <button className={view === 'month' ? 'on' : ''} onClick={() => switchView('month')}>
              월
            </button>
          </div>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go(data.prevKey)} aria-label="이전">
            <Icon name="left" />
          </button>
          {key !== current && (
            <button className="btn btn-ghost btn-sm" onClick={() => go(current)}>
              {view === 'week' ? '이번 주' : '이번 달'}
            </button>
          )}
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go(data.nextKey)} aria-label="다음">
            <Icon name="right" />
          </button>
          {data && view === 'week' && (
            <button className="btn btn-sm" onClick={() => setTextOpen(true)} title="사내 주간보고용 업무 내용 정리">
              <Icon name="report" size={14} /> 업무 내용
            </button>
          )}
          {data && view === 'week' && <ClassifyButton week={key} hasItems={data.items.some((i) => i.planned)} />}
        </div>
      </header>
      {data && view === 'week' && <Summary board={data} />}

      {error && <ErrorBox error={error} />}
      {isLoading && <Loading />}
      {textOpen && view === 'week' && <WorkTextDialog week={key} onClose={() => setTextOpen(false)} />}
      {data && view === 'month' && <MonthOverview board={data} onWeek={(w) => go(w, 'week')} />}
      {data && view === 'week' && (
        <>
          {data.carryover.length > 0 && <Carryover board={data} />}
          <TodoGroups board={data} openId={openId} setOpenId={setOpenId} />
          <Unassigned board={data} />
        </>
      )}
    </div>
  )
}

function Summary({ board }: { board: Board }) {
  const s = board.stats
  const pct = s.itemCount ? Math.round((s.doneCount / s.itemCount) * 100) : 0
  return (
    <div className="todo-summary">
      <div className="todo-progress" title={`완료 ${s.doneCount}/${s.itemCount}`}>
        <span style={{ width: `${pct}%` }} />
      </div>
      <span>
        <b>{s.doneCount}</b>/{s.itemCount} 완료
      </span>
      <span className="sep" />
      <span title="이번 주 Claude 세션 작업 시간 중 할 일에 연결된 시간">
        연결 <b>{fmtShort(s.linkedSec)}</b> / {fmtShort(s.activeSec)}
      </span>
      <span className="sep" />
      <UsageButton from={board.from} to={board.to} title={board.label}>
        사용량 <b>{fmtUsd(s.costUsd)}</b>
      </UsageButton>
    </div>
  )
}

function ClassifyButton({ week, hasItems }: { week: string; hasItems: boolean }) {
  const running = useRunningJob(`classify:${week}`)
  const start = useStartJob((w: string) => api.classify(w))
  return (
    <button
      className="btn btn-ghost btn-sm"
      disabled={!hasItems || !!running || start.isPending}
      onClick={() => start.mutate(week)}
      title={hasItems ? '직접 정한 연결은 두고, 남은 세션 작업을 AI 가 할 일에 붙여요' : '먼저 할 일을 추가하세요'}
    >
      {running ? <div className="spinner" /> : <Icon name="sparkle" size={14} />}
      {running ? '분류 중' : 'AI 분류'}
    </button>
  )
}

// ───────── 아이콘 (Linear 풍) ─────────

function PriorityBars({ priority }: { priority: WorkPriority }) {
  const n = priority === 'HIGH' ? 3 : priority === 'MEDIUM' ? 2 : 1
  const on = priority === 'HIGH' ? 'var(--accent)' : priority === 'MEDIUM' ? 'var(--sun)' : 'var(--text-subtle)'
  return (
    <span className="pbars" title={`우선순위 ${PRIORITY[priority].label}`}>
      <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden>
        {[0, 1, 2].map((i) => (
          <rect key={i} x={1 + i * 4.5} y={10 - i * 3.5} width="3" height={3 + i * 3.5} rx="0.8" fill={i < n ? on : 'var(--border)'} />
        ))}
      </svg>
    </span>
  )
}

/** 상태 아이콘 = 체크박스. 누르면 완료 ↔ 진행 중 */
function StatusButton({ item, save }: { item: WorkItem; save: (p: WorkItemPatch) => void }) {
  const done = item.status === 'DONE'
  return (
    <button
      className="status-btn"
      onClick={() => save({ status: done ? 'IN_PROGRESS' : 'DONE' })}
      title={`${STATUS[item.status].label} — 누르면 ${done ? '진행 중으로' : '완료'}`}
      aria-label={done ? '완료 취소' : '완료'}
    >
      <StatusIcon status={item.status} />
    </button>
  )
}

function StatusSegment({ value, onSave }: { value: WorkStatus; onSave: (p: WorkItemPatch) => void }) {
  return (
    <div className="status-seg" role="radiogroup" aria-label="상태">
      {STATUS_ORDER.map((s) => (
        <button
          key={s}
          role="radio"
          aria-checked={value === s}
          className={value === s ? 'on' : ''}
          style={value === s ? tint(STATUS[s].color, 35, 12) : undefined}
          onClick={() => onSave({ status: s })}
        >
          <StatusIcon status={s} size={13} /> {STATUS[s].label}
        </button>
      ))}
    </div>
  )
}

// ───────── 지난주 이월 ─────────

function Carryover({ board }: { board: Board }) {
  const [checked, setChecked] = useState<Set<number>>(() => new Set(board.carryover.map((i) => i.id)))
  const plan = useWorkMutation(async (ids: number[]) => {
    for (const id of ids) await api.planItem(board.weekKey, id)
  })
  return (
    <div className="carry-callout">
      <div className="carry-head">
        <Icon name="refresh" size={15} />
        <span>지난주에 끝나지 않은 할 일 {board.carryover.length}개</span>
        <button className="btn btn-sm btn-primary" disabled={checked.size === 0 || plan.isPending} onClick={() => plan.mutate([...checked])}>
          {checked.size === board.carryover.length ? '모두 가져오기' : `${checked.size}개 가져오기`}
        </button>
      </div>
      {board.carryover.map((i) => (
        <label key={i.id} className="carry-item">
          <input
            type="checkbox"
            checked={checked.has(i.id)}
            onChange={(e) =>
              setChecked((prev) => {
                const next = new Set(prev)
                if (e.target.checked) next.add(i.id)
                else next.delete(i.id)
                return next
              })
            }
          />
          <StatusIcon status={i.status} size={14} />
          <span>{i.title}</span>
          <span className="subtle">{i.category ?? ''}</span>
        </label>
      ))}
    </div>
  )
}

// ───────── 할 일 목록 ─────────

function TodoGroups({ board, openId, setOpenId }: { board: Board; openId: number | null; setOpenId: (id: number | null) => void }) {
  const groups = new Map<string, WorkItem[]>()
  for (const c of board.categories) groups.set(c, [])
  for (const item of board.items) {
    const key = item.category ?? NO_CATEGORY
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(item)
  }
  return (
    <>
      {[...groups.entries()].map(([category, items]) => {
        const done = items.filter((i) => i.status === 'DONE').length
        return (
          <section key={category} className="todo-group">
            <h3 className="todo-heading">
              {category}
              {items.length > 0 && (
                <>
                  <span className="cat-badge" title={`완료 ${done} / ${items.length}`}>
                    {done}/{items.length}
                  </span>
                  <span className="cat-mini" aria-hidden>
                    <span style={{ width: `${(done / items.length) * 100}%` }} />
                  </span>
                </>
              )}
            </h3>
            <div className="t-list">
              {items.map((item) => (
                <TodoItem
                  key={item.id}
                  item={item}
                  board={board}
                  open={openId === item.id}
                  onOpen={() => setOpenId(item.id)}
                  onClose={() => setOpenId(null)}
                />
              ))}
              <AddTodo category={category === NO_CATEGORY ? null : category} week={board.weekKey} showWeek={board.kind === 'month'} />
            </div>
          </section>
        )
      })}
    </>
  )
}

function TodoItem({ item, board, open, onOpen, onClose }: { item: WorkItem; board: Board; open: boolean; onOpen: () => void; onClose: () => void }) {
  const update = useUpdateItem()
  const save = (patch: WorkItemPatch) => update.mutate({ id: item.id, patch })
  const ref = useRef<HTMLDivElement>(null)
  const done = item.status === 'DONE'
  const due = dDay(item.dueDate, item.status)
  const memoLine = item.memo?.split('\n').map((l) => l.replace(/^[-*•]\s*/, '').trim()).find(Boolean)

  // 카드 바깥을 누르면 닫는다(다른 줄을 누르면 그 줄이 열림)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && onClose()
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open, onClose])

  if (open) {
    return (
      <div ref={ref} className={`t-item t-card${done ? ' done' : ''}`}>
        <div className="t-card-head">
          <StatusButton item={item} save={save} />
          <TextCell value={item.title} onCommit={(v) => save({ title: v })} className="t-card-title" required />
          <button className="btn btn-ghost icon-btn btn-sm" onClick={onClose} aria-label="닫기" title="닫기 (Esc)">
            <Icon name="x" size={14} />
          </button>
        </div>
        <div className="t-card-body">
          <MemoEditor value={item.memo} onSave={(memo) => save({ memo })} placeholder="메모 — 세부 할 일, 진행 상황, 참고 링크…" />
          <div className="t-props">
            <StatusSegment value={item.status} onSave={save} />
            <div className="t-props-row">
              <label className="t-prop">
                <span>우선순위</span>
                <PrioritySelect value={item.priority} onSave={save} />
              </label>
              <label className="t-prop">
                <span>카테고리</span>
                <CategorySelect value={item.category} categories={board.categories} onSave={save} />
              </label>
              <div className="t-prop">
                <span>마감</span>
                <DueDateInput value={item.dueDate} status={item.status} onSave={save} />
              </div>
            </div>
          </div>
          <TodoSessions item={item} board={board} />
        </div>
        <div className="t-card-foot">
          <Link to={`/work/${item.id}`}>상세 페이지 →</Link>
          {item.activeSec > 0 && (
            <span className="subtle num">
              {board.kind === 'week' ? '이번 주' : '이번 달'} {fmtShort(item.activeSec)} · {fmtUsd(item.costUsd)}
            </span>
          )}
          <RowMenu item={item} board={board} />
        </div>
      </div>
    )
  }

  const monthWeeks = board.kind === 'month' ? item.weeks.filter((w) => board.weeks.includes(w)) : []
  const hasSub = item.sessions.length > 0 || !!memoLine || monthWeeks.length > 0
  return (
    <div className={`t-item${done ? ' done' : ''}`} onClick={(e) => !isControl(e) && onOpen()}>
      <div className="t-line">
        <StatusButton item={item} save={save} />
        <span className="t-title">{item.title}</span>
        {!item.planned && (
          <span className="meta-pill" style={tint('var(--c-orange)')} title="이번 주 계획엔 없지만 연결된 작업이 있어요">
            계획 외
          </span>
        )}
        <span className="t-right">
          {item.status === 'ON_HOLD' && <span className="t-hold">보류</span>}
          {item.dueDate && (
            <span className={`t-due ${due?.tone ?? 'ok'}`} title={due ? `마감 ${due.text}` : '마감'}>
              <Icon name="calendar" size={12} /> {fmtDay(item.dueDate)}
            </span>
          )}
          <PriorityBars priority={item.priority} />
          <span className="t-time num" title={item.activeSec > 0 ? `이번 주 ${fmtShort(item.activeSec)} · ${fmtUsd(item.costUsd)}` : '이번 주 연결된 작업 없음'}>
            {item.activeSec > 0 ? fmtShort(item.activeSec) : ''}
          </span>
        </span>
        <RowMenu item={item} board={board} />
      </div>
      {hasSub && (
        <div className="t-sub">
          {monthWeeks.length > 0 && <span className="t-weeks">{monthWeeks.map((w) => monthWeek(w).n).join('·')}주차</span>}
          {item.sessions.slice(0, 3).map((x) => (
            <span key={x.sessionId} className="t-sess" title={`${x.title}${x.live ? (x.busy ? ' · 작업 중' : ' · 열려 있음') : ''}`}>
              <span className={`dot ${x.busy ? 'busy' : x.live ? 'live' : ''}`} />
              {x.title}
            </span>
          ))}
          {item.sessions.length > 3 && <span className="t-more">+{item.sessions.length - 3}</span>}
          {memoLine && <span className="t-memo">{memoLine}</span>}
        </div>
      )}
    </div>
  )
}

function AddTodo({ category, week, showWeek }: { category: string | null; week: string; showWeek?: boolean }) {
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState('')
  const create = useWorkMutation((t: string) =>
    api.createWorkItem({ title: t, category: category ?? undefined, weekKey: week, status: 'PLANNED', priority: 'MEDIUM' }),
  )
  if (!editing) {
    return (
      <button className="t-add" onClick={() => setEditing(true)}>
        <span className="plus">+</span> 새 할 일
        {showWeek && <span className="t-add-week">→ {weekLabel(week)}</span>}
      </button>
    )
  }
  const submit = () => {
    const t = title.trim()
    if (!t) {
      setEditing(false)
      return
    }
    create.mutate(t, { onSuccess: () => setTitle('') })
  }
  return (
    <div className="t-item adding">
      <div className="t-line">
        <span className="status-btn">
          <StatusIcon status="PLANNED" />
        </span>
        <input
          className="t-input"
          autoFocus
          placeholder="할 일 입력 후 Enter — 이어서 계속 입력할 수 있어요 (Esc 닫기)"
          value={title}
          disabled={create.isPending}
          onChange={(e) => setTitle(e.target.value)}
          onBlur={() => !title.trim() && setEditing(false)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.nativeEvent.isComposing) submit()
            if (e.key === 'Escape') {
              setTitle('')
              setEditing(false)
            }
          }}
        />
      </div>
    </div>
  )
}

function RowMenu({ item, board }: { item: WorkItem; board: Board }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])
  const unplan = useWorkMutation(() => api.unplanItem(board.weekKey, item.id))
  const plan = useWorkMutation(() => api.planItem(board.weekKey, item.id))
  const remove = useWorkMutation(() => api.deleteWorkItem(item.id))
  return (
    <div className="row-menu" ref={ref}>
      <button className="todo-more" onClick={() => setOpen(!open)} aria-label="더보기">
        ⋯
      </button>
      {open && (
        <div className="menu">
          <Link to={`/work/${item.id}`} className="menu-link">
            상세 페이지 열기
          </Link>
          {board.kind === 'week' &&
            (item.planned ? (
              <button onClick={() => unplan.mutate(undefined)}>이번 주에서 빼기</button>
            ) : (
              <button onClick={() => plan.mutate(undefined)}>이번 주 계획에 넣기</button>
            ))}
          <button
            className="danger"
            onClick={() => {
              if (window.confirm(`"${item.title}" 할 일을 삭제할까요? 메모와 연결 기록도 함께 지워져요.`)) remove.mutate(undefined)
            }}
          >
            삭제
          </button>
        </div>
      )}
    </div>
  )
}

// ───────── 연결된 작업 · 계획 외 작업 ─────────

/** 할 일에 붙은 세션 목록 + "세션 추가" */
function TodoSessions({ item, board }: { item: WorkItem; board: Board }) {
  const detach = useWorkMutation((sid: string) => api.detachSession(item.id, sid))
  return (
    <div className="todo-sessions">
      <div className="todo-sub">
        연결된 세션 {item.sessions.length}
        {item.activeSec > 0 && (
          <span className="subtle">
            · {board.kind === 'week' ? '이번 주' : '이번 달'} {fmtShort(item.activeSec)} · {fmtUsd(item.costUsd)}
          </span>
        )}
      </div>
      {item.sessions.map((x) => {
        const latest = [...x.days].reverse().find((d) => d.summary)?.summary
        return (
          <div key={x.sessionId} className="sess-row">
            <span className={`dot ${x.busy ? 'busy' : x.live ? 'live' : ''}`} title={x.busy ? '작업 중' : x.live ? '열려 있음' : '닫힘'} />
            <div className="link-main">
              <div className="link-title">
                {x.taskKey ? <Link to={`/sessions/${x.taskKey}`}>{x.title}</Link> : <span>{x.title}</span>}
                <ProjectLabel project={x.project} />
                {!x.pinned && <span className="src src-auto" title="이번 주 작업만 연결 — 앞으로의 작업은 자동으로 안 붙어요">이번 주만</span>}
                {x.activeSec > 0 && (
                  <span className="subtle num">
                    {fmtShort(x.activeSec)} · {fmtUsd(x.costUsd)}
                  </span>
                )}
              </div>
              <div className="link-summary">
                {x.days.length > 0
                  ? x.days.map((d) => `${fmtDay(d.day)} ${fmtShort(d.activeSec)}`).join(' · ')
                  : `${board.kind === 'week' ? '이번 주' : '이번 달'} 작업 아직 없음 — 이 세션에서 일하면 여기로 쌓여요`}
                {latest && <> — {latest}</>}
              </div>
            </div>
            <button className="btn btn-ghost btn-sm" onClick={() => detach.mutate(x.sessionId)} disabled={detach.isPending} title="이 할 일에서 연결 해제">
              해제
            </button>
          </div>
        )
      })}
      <SessionPicker item={item} board={board} />
    </div>
  )
}

function SessionPicker({ item, board, compact }: { item: WorkItem; board: Board; compact?: boolean }) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const ref = useRef<HTMLDivElement>(null)
  const candidates = useQuery({
    queryKey: ['candidates', board.kind, board.key],
    queryFn: () => api.sessionCandidates(board.kind, board.key),
    enabled: open,
    staleTime: 0,
  })
  const attach = useWorkMutation((sid: string) => api.attachSession(item.id, sid, board.kind, board.key))
  useEffect(() => {
    if (!open) return
    const close = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', close)
    return () => document.removeEventListener('mousedown', close)
  }, [open])

  const needle = q.trim().toLowerCase()
  const list = (candidates.data ?? []).filter(
    (c) => !needle || c.title.toLowerCase().includes(needle) || (c.project ?? '').toLowerCase().includes(needle),
  )
  const live = list.filter((c) => c.live)
  const recent = list.filter((c) => !c.live)

  const row = (c: (typeof list)[number]) => {
    const mine = c.linkedItemId === item.id
    return (
      <button
        key={c.sessionId}
        className="pick-row"
        disabled={mine || attach.isPending}
        onClick={() => attach.mutate(c.sessionId, { onSuccess: () => setOpen(false) })}
      >
        <span className={`dot ${c.busy ? 'busy' : c.live ? 'live' : ''}`} />
        <span className="pick-main">
          <span className="pick-title">{c.title}</span>
          <span className="pick-sub">
            {c.project ?? '-'}
            {c.kind === 'bg' && ' · 백그라운드'}
            {c.weekActiveSec > 0 && ` · ${board.kind === 'week' ? '이번 주' : '이번 달'} ${fmtShort(c.weekActiveSec)}`}
          </span>
        </span>
        {mine ? (
          <span className="pick-tag on">연결됨</span>
        ) : c.linkedItemTitle ? (
          <span className="pick-tag" title={`지금은 "${c.linkedItemTitle}"에 연결돼 있어요. 고르면 이 할 일로 옮겨요`}>
            → {c.linkedItemTitle}
          </span>
        ) : null}
      </button>
    )
  }

  return (
    <div className="picker" ref={ref}>
      {compact ? (
        <button className="chip-add" onClick={() => setOpen(!open)} title="이 할 일에 세션 연결">
          + 세션
        </button>
      ) : (
        <button className="todo-add sess-add" onClick={() => setOpen(!open)}>
          <span className="plus">+</span> 세션 추가
        </button>
      )}
      {open && (
        <div className="picker-pop">
          <input className="picker-search" autoFocus placeholder="세션 검색" value={q} onChange={(e) => setQ(e.target.value)} />
          {candidates.isLoading && <div className="pick-empty">불러오는 중…</div>}
          {candidates.data && list.length === 0 && <div className="pick-empty">해당하는 세션이 없어요</div>}
          {live.length > 0 && <div className="pick-group">열려 있는 세션 {live.length}</div>}
          {live.map(row)}
          {recent.length > 0 && <div className="pick-group">최근 7일</div>}
          {recent.map(row)}
          <div className="pick-foot">
            고르면 {board.kind === 'week' ? '이번 주' : '이번 달'} 작업이 이 할 일로 오고, 앞으로 그 세션에서 하는 작업도 계속 여기로 쌓여요.
          </div>
        </div>
      )}
    </div>
  )
}

function Unassigned({ board }: { board: Board }) {
  const [open, setOpen] = useState(true)
  if (board.unassigned.length === 0) return null
  const sec = board.unassigned.reduce((s, u) => s + u.activeSec, 0)
  return (
    <section className="todo-group loose-group">
      <button className="todo-heading toggle" onClick={() => setOpen(!open)} aria-expanded={open}>
        <Icon name="chevron" size={13} className={open ? 'rot' : ''} />
        계획 외 작업
        <span className="count">
          {board.unassigned.length} · {fmtShort(sec)}
        </span>
      </button>
      {open && (
        <>
          <p className="loose-hint">
            할 일에 연결되지 않은 Claude 세션 작업이에요. 오른쪽에서 정리하세요.
            {board.hiddenShort > 0 && ` (3분 미만 짧은 작업 ${board.hiddenShort}건은 숨김)`}
          </p>
          {board.unassigned.map((u) => (
            <LooseRow key={u.sessionId} u={u} board={board} />
          ))}
        </>
      )}
    </section>
  )
}

function LooseRow({ u, board }: { u: UnassignedSession; board: Board }) {
  const days = u.days.map((d) => d.day)
  const link = useWorkMutation(({ id, pin }: { id: number | null; pin: boolean }) =>
    api.link({ sessionId: u.sessionId, days, workItemId: id, pin }),
  )
  const promote = useWorkMutation(() => api.workItemFromSession(u.sessionId, board.weekKey, days))
  const hide = useWorkMutation(() => api.updateSession(u.sessionId, { hidden: true }))
  const latest = [...u.days].reverse().find((d) => d.summary)?.summary
  const items = board.items

  const act = (v: string) => {
    const [kind, id] = v.split(':')
    if (kind === 'link') link.mutate({ id: Number(id), pin: false })
    if (kind === 'pin') link.mutate({ id: Number(id), pin: true })
    if (kind === 'new') promote.mutate(undefined)
    if (kind === 'confirm') link.mutate({ id: null, pin: false })
    if (kind === 'hide') hide.mutate(undefined)
  }

  return (
    <div className={`loose${u.confirmed ? ' confirmed' : ''}`}>
      <span className="loose-dot" />
      <div className="link-main">
        <div className="link-title">
          <Link to={`/sessions/${u.taskKey}`}>{u.title}</Link>
          <ProjectLabel project={u.project} />
          {u.aiDecided && <span className="src src-auto" title="AI 가 이번 주 할 일 중 해당 없음으로 판단">AI: 해당 없음</span>}
          {u.confirmed && <span className="src src-manual">계획 외 확정</span>}
          {u.costUsd > 0 && <span className="subtle num">{fmtUsd(u.costUsd)}</span>}
        </div>
        <div className="link-summary">
          {u.days.map((d) => `${fmtDay(d.day)} ${fmtShort(d.activeSec)}`).join(' · ')}
          {latest && <> — {latest}</>}
        </div>
      </div>
      <select className="mini-select loose-action" value="" onChange={(e) => e.target.value && act(e.target.value)}>
        <option value="">정리하기…</option>
        {items.length > 0 && (
          <optgroup label="할 일에 연결 (이번 주 작업만)">
            {items.map((i) => (
              <option key={i.id} value={`link:${i.id}`}>
                {i.title}
              </option>
            ))}
          </optgroup>
        )}
        {items.length > 0 && (
          <optgroup label="항상 이 할 일로 (앞으로의 작업까지)">
            {items.map((i) => (
              <option key={i.id} value={`pin:${i.id}`}>
                {i.title}
              </option>
            ))}
          </optgroup>
        )}
        <optgroup label="그 밖에">
          <option value="new">+ 새 할 일로 등록</option>
          {!u.confirmed && <option value="confirm">계획 외로 확정</option>}
          <option value="hide">숨기기 (개인 일)</option>
        </optgroup>
      </select>
    </div>
  )
}
