import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLastJob, useRunningJob, useSettings, useStartJob } from '../api/hooks'
import { api } from '../api/client'
import type { Insight, InsightPatch, InsightTarget, MetricDef, Observer, ObsBreakdown } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel } from '../components/common'
import { fmtDateTime, fmtDuration, fmtShort, fmtUsd, parseYmd, weekKey } from '../lib/format'

// 에이전트 옵저버 — Claude 세션이 시간·토큰을 어디에 쓰는지(지표·병목) 보고,
// 개선 규칙을 md 에 넣은 뒤 그 지표가 실제로 좋아졌는지(도입 전 14일 ↔ 도입 후 14일) 기록한다

/** 범위 = 설정의 "저장소 → 카테고리" 에 쓰인 카테고리(세션의 표시 이름). 표가 비면 전체만 */
function useScopes(): string[] {
  const { data } = useSettings()
  return [...new Set((data?.projects ?? []).map((p) => p.category))]
}

export function ObserverPage() {
  const [params, setParams] = useSearchParams()
  const week = params.get('week') ?? weekKey(new Date())
  const scope = params.get('scope')
  const go = (next: { week?: string; scope?: string | null }) => {
    const p: Record<string, string> = { week: next.week ?? week }
    const sc = next.scope === undefined ? scope : next.scope
    if (sc) p.scope = sc
    setParams(p, { replace: true })
  }
  const { data, isLoading, error } = useQuery({ queryKey: ['observer', week, scope], queryFn: () => api.observer(week, scope) })
  const insights = useQuery({ queryKey: ['insights'], queryFn: () => api.insights() })

  const scopes = useScopes()
  const scopeSwitch = scopes.length > 0 && (
    <div className="segmented">
      {[null, ...scopes].map((key) => (
        <button key={key ?? 'all'} className={scope === key ? 'on' : ''} onClick={() => go({ scope: key })}>
          {key ?? '전체'}
        </button>
      ))}
    </div>
  )
  const body = (
    <>
      <div className="cal-toolbar">
        <div className="nav-btns">
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go({ week: data.prevKey })} aria-label="이전 주">
            <Icon name="left" />
          </button>
          <h2 style={{ minWidth: 260, textAlign: 'center' }}>{data?.label ?? week}</h2>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go({ week: data.nextKey })} aria-label="다음 주">
            <Icon name="right" />
          </button>
        </div>
        <ProposeButton week={week} scope={scope} />
      </div>

      {error && <ErrorBox error={error} />}
      {isLoading && <Loading />}
      {data && (
        <div className="ob">
          {data.current.counts.toolCalls === 0 && (
            <div className="ob-note">
              이 주에는 도구·턴 기록이 없어요.
              {data.coverageFrom && ` 원본 세션 파일이 남아 있던 기간(${data.coverageFrom}~)만 볼 수 있어요.`}
            </div>
          )}
          <MetricCards observer={data} insights={insights.data ?? []} />
          <TimeSplit b={data.current.breakdown} observer={data} />
          <div className="ob-two">
            <SlowCommands observer={data} />
            <ToolTable observer={data} />
          </div>
          <div className="ob-two">
            <Errors observer={data} />
            <Corrections observer={data} />
          </div>
          <div className="ob-two">
            <CostlySessions observer={data} />
            <div className="ob-stack">
              <Models observer={data} />
              <Rework observer={data} />
            </div>
          </div>
          <InsightSection week={week} defs={data.metricDefs} insights={insights.data} loading={insights.isLoading} />
        </div>
      )}
    </>
  )
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>AI 사용</h1>
          <p>Claude 세션이 시간과 토큰을 어디에 쓰는지 보고, 규칙을 바꾼 뒤 실제로 나아졌는지 기록해요.</p>
        </div>
        {scopeSwitch}
      </div>
      {body}
    </div>
  )
}

// ───────── 지표 ─────────

/** 도구 시간 — 1분 미만은 초로("12s"), 그 위는 fmtShort */
function fmtSec(sec: number): string {
  return sec < 60 ? `${sec}s` : fmtShort(sec)
}

export function fmtMetric(def: MetricDef | undefined, v: number | null | undefined): string {
  if (v == null || !def) return '—'
  switch (def.unit) {
    case '$':
      return fmtUsd(v)
    case '%':
      return `${v.toFixed(v < 10 ? 1 : 0)}%`
    case '회':
      return `${v.toFixed(2)}회`
    case '분':
      return `${v.toFixed(1)}분`
    default:
      return v.toFixed(1)
  }
}

/** 낮을수록 좋은 지표에서 좋아졌으면 good, 나빠졌으면 bad */
function tone(def: MetricDef, cur: number | null | undefined, prev: number | null | undefined): 'good' | 'bad' | '' {
  if (!def.lowerIsBetter || cur == null || prev == null || prev === 0) return ''
  const change = (cur - prev) / Math.abs(prev)
  if (change <= -0.1) return 'good'
  if (change >= 0.1) return 'bad'
  return ''
}

function MetricCards({ observer, insights }: { observer: Observer; insights: Insight[] }) {
  const metricOf = new Map(insights.map((i) => [i.id, i.metric]))
  return (
    <div className="ob-metrics">
      {observer.metricDefs.map((def) => {
        const cur = observer.current.metrics[def.key]
        const prev = observer.previous.metrics[def.key]
        const t = tone(def, cur, prev)
        const marks = observer.trend.map((p) => p.adopted.some((id) => metricOf.get(id) === def.key))
        return (
          <div key={def.key} className={`ob-metric${def.lowerIsBetter ? '' : ' ref'}`} title={def.description}>
            <span className="ob-mlabel">
              {def.label}
              {def.unit.startsWith('/') && <small> {def.unit}</small>}
            </span>
            <b className="num">{fmtMetric(def, cur)}</b>
            <span className={`ob-mprev ${t}`}>
              지난주 {fmtMetric(def, prev)}
              {t === 'good' && ' ↓'}
              {t === 'bad' && ' ↑'}
            </span>
            <Spark values={observer.trend.map((p) => p.metrics[def.key])} marks={marks} />
          </div>
        )
      })}
    </div>
  )
}

/** 최근 8주 꺾은선. 세로 점선 = 그 지표를 노린 제안을 도입한 주 */
function Spark({ values, marks }: { values: (number | null)[]; marks: boolean[] }) {
  const W = 120
  const H = 26
  const nums = values.filter((v): v is number => v != null)
  if (nums.length < 2) return <span className="ob-spark empty">추이 없음</span>
  const max = Math.max(...nums)
  const min = Math.min(...nums)
  const x = (i: number) => (values.length === 1 ? W / 2 : (i / (values.length - 1)) * (W - 4) + 2)
  const y = (v: number) => (max === min ? H / 2 : H - 3 - ((v - min) / (max - min)) * (H - 6))
  let d = ''
  values.forEach((v, i) => {
    if (v == null) return
    d += `${d === '' || values[i - 1] == null ? 'M' : 'L'}${x(i).toFixed(1)} ${y(v).toFixed(1)}`
  })
  const last = values.length - 1
  return (
    <svg className="ob-spark" width={W} height={H} viewBox={`0 0 ${W} ${H}`} aria-hidden>
      {marks.map((m, i) => (m ? <line key={i} x1={x(i)} x2={x(i)} y1={0} y2={H} className="ob-spark-mark" /> : null))}
      <path d={d} fill="none" stroke="currentColor" strokeWidth={1.5} strokeLinejoin="round" strokeLinecap="round" />
      {values[last] != null && <circle cx={x(last)} cy={y(values[last]!)} r={2.5} fill="currentColor" />}
    </svg>
  )
}

// ───────── 시간 분해 ─────────

const SPLIT: { key: keyof ObsBreakdown; label: string; color: string; tip: string }[] = [
  { key: 'modelSec', label: 'Claude 생각·작성', color: 'var(--c-blue)', tip: '턴 시간에서 도구·대기를 뺀 나머지' },
  { key: 'toolSec', label: '도구 실행', color: 'var(--c-purple)', tip: 'Bash·서브에이전트·웹 등 도구가 돈 시간' },
  { key: 'approvalSec', label: '승인 대기(추정)', color: 'var(--c-yellow)', tip: '원래 바로 끝나는 도구(Read·Edit 등)가 2초 넘게 걸린 만큼 — 권한 승인을 기다린 시간으로 본다' },
  { key: 'askSec', label: '질문 응답', color: 'var(--c-orange)', tip: 'AskUserQuestion·계획 승인에 답하기까지' },
  { key: 'nextWaitSec', label: '다음 요청까지', color: 'var(--c-gray)', tip: 'Claude 가 멈춘 뒤 내가 다음 요청을 보내기까지(15분 이하만)' },
]

function TimeSplit({ b, observer }: { b: ObsBreakdown; observer: Observer }) {
  const total = SPLIT.reduce((t, s) => t + b[s.key], 0)
  const c = observer.current.counts
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>시간은 어디로 갔나</h3>
        <span className="subtle">
          작업 {fmtDuration(b.activeSec)} · Claude 턴 {c.turns}회 {fmtDuration(b.turnSec)} · 요청 {c.prompts}개 · 세션 {c.sessions}개
        </span>
      </div>
      {total === 0 ? (
        <p className="subtle ob-empty">턴 기록이 없어요.</p>
      ) : (
        <>
          <div className="ob-split">
            {SPLIT.map((s) =>
              b[s.key] > 0 ? (
                <span key={s.key} style={{ flexGrow: b[s.key], background: s.color }} title={`${s.label} ${fmtDuration(b[s.key])}`} />
              ) : null,
            )}
          </div>
          <div className="ob-legend">
            {SPLIT.map((s) => (
              <span key={s.key} title={s.tip}>
                <i style={{ background: s.color }} />
                {s.label} <b className="num">{fmtShort(b[s.key])}</b>
                <span className="subtle num"> {total > 0 ? Math.round((100 * b[s.key]) / total) : 0}%</span>
              </span>
            ))}
          </div>
        </>
      )}
    </section>
  )
}

// ───────── 병목 목록 ─────────

function SlowCommands({ observer }: { observer: Observer }) {
  const rows = observer.slowCommands
  const max = Math.max(1, ...rows.map((r) => r.totalSec))
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>오래 걸린 명령</h3>
        <span className="subtle">Bash · 총 시간 순</span>
      </div>
      {rows.length === 0 && <p className="subtle ob-empty">없음</p>}
      {rows.map((r) => (
        <div key={r.command} className="ob-bar-row" title={r.sample}>
          <code className="ob-cmd">{r.command}</code>
          <span className="ob-bar">
            <span style={{ width: `${(100 * r.totalSec) / max}%` }} />
          </span>
          <span className="num ob-val">
            {fmtSec(r.totalSec)} · {r.calls}회{r.errors > 0 && <em className="bad"> · 실패 {r.errors}</em>}
          </span>
        </div>
      ))}
    </section>
  )
}

function ToolTable({ observer }: { observer: Observer }) {
  const rows = observer.tools.slice(0, 10)
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>도구별</h3>
        <span className="subtle">서브에이전트 포함 {observer.current.counts.toolCalls}회</span>
      </div>
      <table className="ob-table">
        <thead>
          <tr>
            <th>도구</th>
            <th>횟수</th>
            <th>총 시간</th>
            <th>평균</th>
            <th>실패</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((t) => (
            <tr key={t.tool}>
              <td className="ob-tool">{t.tool.replace(/^mcp__[^_]+(_[^_]+)*__/, 'mcp:')}</td>
              <td className="num">{t.calls}</td>
              <td className="num">{fmtSec(t.totalSec)}</td>
              <td className="num">{t.avgSec < 10 ? `${t.avgSec.toFixed(1)}s` : fmtSec(Math.round(t.avgSec))}</td>
              <td className={`num${t.errors > 0 ? ' bad' : ''}`}>
                {t.errors > 0 ? `${t.errors} (${Math.round((100 * t.errors) / t.calls)}%)` : '—'}
                {t.rejected > 0 && <span className="subtle"> · 거절 {t.rejected}</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function SessionLink({ taskKey, title }: { taskKey: string | null; title: string | null }) {
  if (!title) return null
  return taskKey ? (
    <Link to={`/sessions/${taskKey}`} className="ob-session">
      {title}
    </Link>
  ) : (
    <span className="ob-session">{title}</span>
  )
}

function Errors({ observer }: { observer: Observer }) {
  const c = observer.current.counts
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>도구 실패</h3>
        <span className="subtle">
          {c.errors}건{c.rejected > 0 && ` · 거절 ${c.rejected}`}
        </span>
      </div>
      {observer.errors.length === 0 && <p className="subtle ob-empty">없음</p>}
      <ul className="ob-list">
        {observer.errors.map((e, i) => (
          <li key={i}>
            <div className="ob-list-main">
              <code>{e.tool}</code> <span className="ob-err">{e.error || '(메시지 없음)'}</span>
              {e.count > 1 && <span className="ob-count">×{e.count}</span>}
            </div>
            <div className="ob-list-sub">
              {e.target && <span className="ob-target" title={e.target}>{e.target}</span>}
              <SessionLink taskKey={e.taskKey} title={e.sessionTitle} /> · {fmtDateTime(e.lastAt)}
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}

function Corrections({ observer }: { observer: Observer }) {
  const c = observer.current.counts
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>정정·불만 신호</h3>
        <span className="subtle" title="'아니·엥·ㄴㄴ·안 돼·다시' 같은 낱말로 찾은 추정치 — 오탐 있음">
          요청 {c.prompts}개 중 {c.corrections}개 · 중단 {c.interrupts}회
        </span>
      </div>
      {observer.corrections.length === 0 && <p className="subtle ob-empty">없음</p>}
      <ul className="ob-list">
        {observer.corrections.map((p, i) => (
          <li key={i}>
            <div className="ob-list-main ob-quote">{p.text}</div>
            <div className="ob-list-sub">
              <SessionLink taskKey={p.taskKey} title={p.sessionTitle} /> · {fmtDateTime(p.at)}
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}

function CostlySessions({ observer }: { observer: Observer }) {
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>비용 큰 세션</h3>
        <span className="subtle">이번 주 {fmtUsd(observer.current.counts.costUsd)} (API 환산)</span>
      </div>
      <table className="ob-table">
        <thead>
          <tr>
            <th>세션</th>
            <th>작업</th>
            <th>비용</th>
            <th title="컨텍스트 압축">압축</th>
            <th>실패</th>
            <th title="정정 신호가 있는 요청">정정</th>
            <th title="가장 긴 턴">최장 턴</th>
          </tr>
        </thead>
        <tbody>
          {observer.sessions.map((s) => (
            <tr key={s.sessionId}>
              <td className="ob-sname">
                <ProjectLabel project={s.project} /> <SessionLink taskKey={s.taskKey} title={s.title} />
              </td>
              <td className="num">{fmtShort(s.activeSec)}</td>
              <td className="num">{fmtUsd(s.costUsd)}</td>
              <td className={`num${s.compactions > 0 ? ' warn' : ''}`}>{s.compactions || '—'}</td>
              <td className="num">{s.errors || '—'}</td>
              <td className="num">{s.corrections || '—'}</td>
              <td className="num">{s.longestTurnSec > 0 ? fmtShort(s.longestTurnSec) : '—'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

function Models({ observer }: { observer: Observer }) {
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>모델별 비용</h3>
      </div>
      {observer.models.length === 0 && <p className="subtle ob-empty">없음</p>}
      {observer.models.map((m) => (
        <div key={m.model} className="ob-bar-row">
          <span className="ob-cmd">{m.model.replace(/^claude-/, '')}</span>
          <span className="ob-bar">
            <span style={{ width: `${m.share}%` }} />
          </span>
          <span className="num ob-val">
            {fmtUsd(m.costUsd)} · {Math.round(m.share)}%
          </span>
        </div>
      ))}
    </section>
  )
}

function Rework({ observer }: { observer: Observer }) {
  return (
    <section className="card ob-card">
      <div className="ob-head">
        <h3>여러 번 고친 파일</h3>
        <span className="subtle">한 세션에서 4번 이상</span>
      </div>
      {observer.rework.length === 0 && <p className="subtle ob-empty">없음</p>}
      <ul className="ob-list">
        {observer.rework.map((r, i) => (
          <li key={i}>
            <div className="ob-list-main">
              <span className="ob-target" title={r.path}>{r.path}</span>
              <span className="ob-count">×{r.edits}</span>
            </div>
            <div className="ob-list-sub">
              <SessionLink taskKey={r.taskKey} title={r.sessionTitle} />
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}

// ───────── 개선 제안 · 도입 · 효과 ─────────

function ProposeButton({ week, scope }: { week: string; scope: string | null }) {
  const key = `observer:${week}`
  const running = useRunningJob(key)
  const last = useLastJob(key)
  const start = useStartJob(() => api.observerPropose(week, scope))
  return (
    <div className="ob-propose">
      {last?.status === 'FAILED' && !running && <span className="bad" title={last.message ?? ''}>제안 실패</span>}
      {last?.status === 'DONE' && !running && <span className="subtle">{last.message}</span>}
      <button className="btn btn-primary btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running || start.isPending}>
        <Icon name="sparkle" size={13} />
        {running ? (running.message ?? '제안 만드는 중') : 'AI 개선 제안 받기'}
      </button>
    </div>
  )
}

const GROUPS: { status: Insight['status']; title: string; tip: string }[] = [
  { status: 'adopted', title: '측정 중', tip: '도입 직전 14일과 도입 후 14일을 같은 지표로 비교해요' },
  { status: 'proposed', title: '제안', tip: '문안을 고친 뒤 도입하면 md 에 들어가고 측정이 시작돼요' },
  { status: 'done', title: '결과 확정', tip: '' },
  { status: 'rejected', title: '기각', tip: '' },
]

function useInsightMutation<T>(fn: (arg: T) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['insights'] })
      qc.invalidateQueries({ queryKey: ['observer'] })
    },
  })
}

function InsightSection({ week, defs, insights, loading }: { week: string; defs: MetricDef[]; insights: Insight[] | undefined; loading: boolean }) {
  const targets = useQuery({ queryKey: ['insight-targets'], queryFn: () => api.insightTargets() })
  const create = useInsightMutation(() => api.createInsight({ title: '새 개선안', target: 'global' }, week))
  const [showRejected, setShowRejected] = useState(false)
  const list = insights ?? []
  const measured = list.filter((i) => i.status === 'adopted' || i.status === 'done')
  const count = (v: string) => measured.filter((i) => i.verdict === v).length

  return (
    <section className="ob-insights">
      <div className="ob-ins-head">
        <div>
          <h2>개선 기록</h2>
          <p className="subtle">
            제안 → md 에 도입 → 도입 전후 비교. 도입 {measured.length}건 · 개선 {count('개선')} · 변화 없음 {count('변화 없음')} · 악화{' '}
            {count('악화')} · 측정 중 {count('측정 중')}
          </p>
        </div>
        <button className="btn btn-sm" onClick={() => create.mutate(undefined)} disabled={create.isPending}>
          + 직접 추가
        </button>
      </div>
      {loading && <Loading />}
      {!loading && list.length === 0 && (
        <div className="ob-note">아직 제안이 없어요. 위의 ‘AI 개선 제안 받기’로 이번 주 기록에서 개선안을 뽑거나, 직접 추가하세요.</div>
      )}
      {GROUPS.map((g) => {
        const items = list.filter((i) => i.status === g.status)
        if (items.length === 0) return null
        const hidden = g.status === 'rejected' && !showRejected
        return (
          <div key={g.status} className="ob-group">
            <div className="ob-group-title" title={g.tip}>
              {g.title} <span className="count">{items.length}</span>
              {g.status === 'rejected' && (
                <button className="btn btn-ghost btn-sm" onClick={() => setShowRejected((v) => !v)}>
                  {showRejected ? '접기' : '펼치기'}
                </button>
              )}
            </div>
            {!hidden && items.map((i) => <InsightCard key={i.id} insight={i} defs={defs} targets={targets.data ?? []} />)}
          </div>
        )
      })}
    </section>
  )
}

const VERDICT_TONE: Record<string, string> = { 개선: 'good', 악화: 'bad', '변화 없음': 'flat', '측정 중': 'wait' }

function InsightCard({ insight: i, defs, targets }: { insight: Insight; defs: MetricDef[]; targets: InsightTarget[] }) {
  const scopes = useScopes()
  const def = defs.find((d) => d.key === i.metric)
  const editable = i.status === 'proposed' || i.status === 'rejected'
  const [confirm, setConfirm] = useState<null | 'write' | 'revert' | 'delete'>(null)
  const patch = useInsightMutation((p: InsightPatch) => api.updateInsight(i.id, p))
  const adopt = useInsightMutation((write: boolean) => api.adoptInsight(i.id, write))
  const revert = useInsightMutation(() => api.revertInsight(i.id))
  const close = useInsightMutation(() => api.closeInsight(i.id))
  const reopen = useInsightMutation(() => api.reopenInsight(i.id))
  const remove = useInsightMutation(() => api.deleteInsight(i.id))
  const write = useInsightMutation(() => api.writeInsight(i.id))
  const err = [patch, adopt, revert, close, reopen, remove, write].find((m) => m.isError)?.error as Error | undefined
  const target = targets.find((t) => t.key === i.target)
  const [copied, setCopied] = useState(false)

  return (
    <article className={`ob-ins ${i.status}`}>
      <div className="ob-ins-top">
        {editable ? (
          <InlineText className="ob-ins-title" value={i.title} onSave={(v) => v && patch.mutate({ title: v })} />
        ) : (
          <h4 className="ob-ins-title">{i.title}</h4>
        )}
        <span className="ob-ins-meta subtle">
          #{i.id} · {i.source === 'ai' ? 'AI 제안' : '직접'} · {fmtDateTime(i.createdAt)}
        </span>
      </div>

      {editable ? (
        <InlineText multiline className="ob-ins-problem" value={i.problem ?? ''} placeholder="근거 — 어떤 숫자를 보고 이렇게 바꾸나" onSave={(v) => patch.mutate({ problem: v })} />
      ) : (
        i.problem && <p className="ob-ins-problem">{i.problem}</p>
      )}

      <div className="ob-ins-fields">
        <label>
          지표
          <select value={i.metric ?? ''} disabled={!editable} onChange={(e) => patch.mutate({ metric: e.target.value })}>
            <option value="">— 고르세요</option>
            {defs.map((d) => (
              <option key={d.key} value={d.key}>
                {d.label} {d.lowerIsBetter ? '' : '(참고)'}
              </option>
            ))}
          </select>
        </label>
        <label>
          범위
          <select value={i.scope ?? 'all'} disabled={!editable} onChange={(e) => patch.mutate({ scope: e.target.value })}>
            <option value="all">전체</option>
            {scopes.map((sc) => (
              <option key={sc} value={sc}>
                {sc}
              </option>
            ))}
          </select>
        </label>
        <label className="grow">
          넣을 곳
          <select value={i.target ?? ''} disabled={!editable} onChange={(e) => patch.mutate({ target: e.target.value })}>
            {!i.target && <option value="">— 고르세요</option>}
            {targets.map((t) => (
              <option key={t.key} value={t.key}>
                {t.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className="ob-snippet">
        <div className="ob-snippet-head">
          <span>md 에 넣을 문안</span>
          {i.status !== 'proposed' && i.status !== 'rejected' && (
            <span className={i.applied ? 'good' : 'warn'}>
              {i.applied ? '✓ 파일에 들어 있음' : i.appliedPath ? '⚠ 파일에서 사라짐' : '기록만 (직접 넣음)'}
            </span>
          )}
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => {
              navigator.clipboard?.writeText(i.snippet ?? '')
              setCopied(true)
              window.setTimeout(() => setCopied(false), 1200)
            }}
          >
            <Icon name={copied ? 'check' : 'copy'} size={12} /> {copied ? '복사됨' : '복사'}
          </button>
        </div>
        {editable ? (
          <InlineText multiline mono className="ob-snippet-body" value={i.snippet ?? ''} placeholder="- Claude 에게 줄 규칙 (마크다운 불릿)" onSave={(v) => patch.mutate({ snippet: v })} />
        ) : (
          <pre className="ob-snippet-body">{i.snippet}</pre>
        )}
        {target && (
          <div className="ob-snippet-path subtle">
            {target.kind === 'memory' ? `${target.path}/ 에 feedback 파일 + MEMORY.md 한 줄` : `${target.path}${target.exists ? '' : ' (새 파일)'} 끝에 덧붙임`}
          </div>
        )}
      </div>

      {(i.status === 'adopted' || i.status === 'done') && <Effect insight={i} def={def} />}

      {err && <div className="ob-error">{err.message}</div>}

      {confirm ? (
        <div className="ob-confirm">
          <span>
            {confirm === 'write' && `${target?.path ?? i.target} 에 문안을 넣고 측정을 시작할까요?`}
            {confirm === 'revert' && '파일에서 문안을 빼고 제안 상태로 되돌릴까요? 기준값·측정은 지워져요.'}
            {confirm === 'delete' && '이 제안을 지울까요?'}
          </span>
          <button
            className={`btn btn-sm ${confirm === 'write' ? 'btn-primary' : ''}`}
            onClick={() => {
              if (confirm === 'write') adopt.mutate(true)
              if (confirm === 'revert') revert.mutate(undefined)
              if (confirm === 'delete') remove.mutate(undefined)
              setConfirm(null)
            }}
          >
            {confirm === 'write' ? '넣고 도입' : confirm === 'revert' ? '되돌리기' : '지우기'}
          </button>
          <button className="btn btn-ghost btn-sm" onClick={() => setConfirm(null)}>
            취소
          </button>
        </div>
      ) : (
        <div className="ob-ins-actions">
          {editable && (
            <>
              <button className="btn btn-primary btn-sm" disabled={!i.snippet || !i.target || !i.metric} onClick={() => setConfirm('write')} title={!i.metric ? '지표를 먼저 고르세요' : ''}>
                md 에 넣고 도입
              </button>
              <button className="btn btn-sm" disabled={!i.metric} onClick={() => adopt.mutate(false)} title="이미 손으로 넣었을 때 — 파일은 건드리지 않고 측정만 시작">
                직접 넣었음
              </button>
              {i.status === 'proposed' ? (
                <button className="btn btn-ghost btn-sm" onClick={() => patch.mutate({ status: 'rejected' })}>
                  기각
                </button>
              ) : (
                <button className="btn btn-ghost btn-sm" onClick={() => patch.mutate({ status: 'proposed' })}>
                  제안으로 되살리기
                </button>
              )}
              <button className="btn btn-ghost btn-sm" onClick={() => setConfirm('delete')}>
                삭제
              </button>
            </>
          )}
          {i.status === 'adopted' && (
            <>
              <button className="btn btn-sm" onClick={() => close.mutate(undefined)} title="지금 측정값으로 결과를 확정">
                결과 확정
              </button>
              {!i.applied && i.target && (
                <button className="btn btn-ghost btn-sm" onClick={() => write.mutate(undefined)}>
                  {i.appliedPath ? '파일에 다시 넣기' : '파일에 넣기'}
                </button>
              )}
              <button className="btn btn-ghost btn-sm" onClick={() => setConfirm('revert')}>
                되돌리기
              </button>
            </>
          )}
          {i.status === 'done' && (
            <>
              <button className="btn btn-ghost btn-sm" onClick={() => reopen.mutate(undefined)}>
                다시 측정
              </button>
              <button className="btn btn-ghost btn-sm" onClick={() => setConfirm('revert')} title="효과가 없었으면 규칙을 빼요">
                규칙 빼기
              </button>
            </>
          )}
        </div>
      )}
    </article>
  )
}

function Effect({ insight: i, def }: { insight: Insight; def: MetricDef | undefined }) {
  const verdict = i.verdict ?? '측정 중'
  const progress = i.afterFrom && i.afterTo ? Math.min(14, Math.round((parseYmd(i.afterTo).getTime() - parseYmd(i.afterFrom).getTime()) / 86400000) + 1) : 0
  const md = (s: string | null) => (s ? `${parseYmd(s).getMonth() + 1}/${parseYmd(s).getDate()}` : '')
  return (
    <div className="ob-effect">
      <div className="ob-effect-nums">
        <div>
          <span className="subtle">도입 전 {md(i.baselineFrom)}~{md(i.baselineTo)}</span>
          <b className="num">{fmtMetric(def, i.baseline)}</b>
        </div>
        <span className="ob-arrow">→</span>
        <div>
          <span className="subtle">
            도입 후 {md(i.afterFrom)}~{md(i.afterTo)}
            {i.status === 'adopted' && ` · 일한 날 ${i.afterDays}일`}
          </span>
          <b className="num">{fmtMetric(def, i.after)}</b>
        </div>
        <span className={`ob-verdict ${VERDICT_TONE[verdict] ?? 'flat'}`}>
          {verdict}
          {i.deltaPct != null && verdict !== '측정 중' && ` ${i.deltaPct > 0 ? '+' : ''}${Math.round(i.deltaPct)}%`}
        </span>
      </div>
      {i.status === 'adopted' && (
        <div className="ob-progress" title={`측정 창 14일 중 ${progress}일 지남`}>
          <span style={{ width: `${(progress / 14) * 100}%` }} />
        </div>
      )}
      <div className="subtle ob-effect-foot">
        {def?.label ?? i.metric} · {i.scope ?? '전체'} · 도입 {fmtDateTime(i.adoptedAt)}
        {i.closedAt && ` · 확정 ${fmtDateTime(i.closedAt)}`}
      </div>
    </div>
  )
}

/** 누르면 바로 고치는 글. 포커스를 잃을 때 저장, Esc 는 되돌림 */
function InlineText({
  value,
  onSave,
  className,
  placeholder,
  multiline,
  mono,
}: {
  value: string
  onSave: (v: string) => void
  className?: string
  placeholder?: string
  multiline?: boolean
  mono?: boolean
}) {
  const [text, setText] = useState(value)
  const area = useRef<HTMLTextAreaElement>(null)
  useEffect(() => setText(value), [value])
  useEffect(() => {
    const el = area.current
    if (!el) return
    el.style.height = 'auto'
    el.style.height = `${el.scrollHeight + 2}px`
  }, [text])
  const commit = () => {
    if (text.trim() !== value.trim()) onSave(text.trim())
  }
  if (multiline) {
    return (
      <textarea
        ref={area}
        className={`ob-inline ${className ?? ''}${mono ? ' mono' : ''}`}
        value={text}
        placeholder={placeholder}
        rows={1}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Escape' && setText(value)}
      />
    )
  }
  return (
    <input
      className={`ob-inline ${className ?? ''}`}
      value={text}
      placeholder={placeholder}
      onChange={(e) => setText(e.target.value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') (e.target as HTMLInputElement).blur()
        if (e.key === 'Escape') setText(value)
      }}
    />
  )
}
