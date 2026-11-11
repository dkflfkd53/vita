import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLastJob, useRunningJob, useSettings, useStartJob } from '../api/hooks'
import { api } from '../api/client'
import type { DietItem, Insight, Outcome, InsightPatch, InsightTarget, InsightTier, MetricDef, Observer, ObsBreakdown, PeriodKind, RuleLoad } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel } from '../components/common'
import { UsageOverviewSection } from '../components/UsageOverview'
import { PERIOD_NAME, PREV_PERIOD, THIS_PERIOD, fmtDateTime, fmtDuration, fmtShort, fmtUsd, parseYmd, periodKeyOf } from '../lib/format'

// 에이전트 옵저버 — Claude 세션이 시간·토큰을 어디에 쓰는지(지표·병목) 보고,
// 개선 규칙을 md 에 넣은 뒤 그 지표가 실제로 좋아졌는지(도입 전 14일 ↔ 도입 후 14일) 기록한다

/** 범위 = 설정의 "저장소 → 카테고리" 에 쓰인 카테고리(세션의 표시 이름). 표가 비면 전체만 */
function useScopes(): string[] {
  const { data } = useSettings()
  const hidden = new Set((data?.categories ?? []).filter((c) => c.hidden).map((c) => c.name))
  return [...new Set((data?.projects ?? []).map((p) => p.category))].filter((c) => !hidden.has(c))
}

export function ObserverPage() {
  const [params, setParams] = useSearchParams()
  // 기간 = 주·월·연. 예전 주소(?week=)도 읽는다
  const kind: PeriodKind = params.get('p') === 'month' || params.get('p') === 'year' ? (params.get('p') as PeriodKind) : 'week'
  const key = params.get('key') ?? (kind === 'week' ? params.get('week') : null) ?? periodKeyOf(kind)
  const scope = params.get('scope')
  // 탭 = 사용량(기본) · 작업 패턴 · 개선 — 주소에 남겨 다시 들어와도 그 탭
  const tab: Tab = params.get('tab') === 'pattern' || params.get('tab') === 'improve' ? (params.get('tab') as Tab) : 'usage'
  const go = (next: { kind?: PeriodKind; key?: string; scope?: string | null; tab?: Tab }) => {
    const k = next.kind ?? kind
    const p: Record<string, string> = { key: next.key ?? (next.kind && next.kind !== kind ? periodKeyOf(k) : key) }
    if (k !== 'week') p.p = k
    const sc = next.scope === undefined ? scope : next.scope
    if (sc) p.scope = sc
    const t = next.tab ?? tab
    if (t !== 'usage') p.tab = t
    setParams(p, { replace: true })
  }
  const { data, isLoading, error } = useQuery({
    queryKey: ['observer', kind, key, scope],
    queryFn: () => api.observer(kind, key, scope),
    enabled: tab === 'pattern',
  })
  const insights = useQuery({ queryKey: ['insights'], queryFn: () => api.insights() })
  const mustPending = (insights.data ?? []).filter((i) => i.status === 'proposed' && i.tier === 'must').length
  const proposed = (insights.data ?? []).filter((i) => i.status === 'proposed').length

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
      <div className="page-tabs" role="tablist" aria-label="AI 사용 보기">
        {TABS.map((t) => (
          <button key={t.key} role="tab" aria-selected={tab === t.key} className={tab === t.key ? 'on' : ''} onClick={() => go({ tab: t.key })} title={t.tip}>
            {t.name}
            {t.key === 'improve' && proposed > 0 && (
              <span className={`tab-badge${mustPending > 0 ? ' must' : ''}`} title={mustPending > 0 ? `처리 안 한 필수 제안 ${mustPending}개` : `제안 ${proposed}개`}>
                {mustPending > 0 ? mustPending : proposed}
              </span>
            )}
          </button>
        ))}
      </div>
      {tab !== 'improve' && (
      <div className="cal-toolbar">
        <div className="nav-btns">
          <div className="segmented" role="tablist" aria-label="기간 단위">
            {(['week', 'month', 'year'] as PeriodKind[]).map((k) => (
              <button key={k} role="tab" aria-selected={kind === k} className={kind === k ? 'on' : ''} onClick={() => go({ kind: k })}>
                {PERIOD_NAME[k]}
              </button>
            ))}
          </div>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go({ key: data.prevKey })} aria-label={`이전 ${PERIOD_NAME[kind]}`}>
            <Icon name="left" />
          </button>
          <h2 style={{ minWidth: 200, textAlign: 'center' }}>{data?.label ?? key}</h2>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => data && go({ key: data.nextKey })} aria-label={`다음 ${PERIOD_NAME[kind]}`}>
            <Icon name="right" />
          </button>
          {key !== periodKeyOf(kind) && (
            <button className="btn btn-ghost btn-sm" onClick={() => go({ key: periodKeyOf(kind) })}>
              {THIS_PERIOD[kind]}
            </button>
          )}
        </div>
      </div>
      )}

      {tab === 'usage' && <UsageOverviewSection kind={kind} periodKey={key} scope={scope} />}
      {tab === 'pattern' && error && <ErrorBox error={error} />}
      {tab === 'pattern' && isLoading && <Loading />}
      {tab === 'pattern' && data && (
        <div className="ob">
          {data.current.counts.toolCalls === 0 && (
            <div className="ob-note">
              이 기간에는 도구·턴 기록이 없어요.
              {data.coverageFrom && ` 원본 세션 파일이 남아 있던 기간(${data.coverageFrom}~)만 볼 수 있어요.`}
            </div>
          )}
          <Highlights observer={data} />
          <MetricCards observer={data} insights={insights.data ?? []} />
          <TimeSplit b={data.current.breakdown} observer={data} />
          <details className="ob-more">
            <summary>
              <Icon name="chevron" size={12} /> 자세한 표 — 오래 걸린 명령·도구·실패·정정 요청·비싼 세션·여러 번 고친 파일
            </summary>
            <div className="ob">
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
                <Rework observer={data} />
              </div>
            </div>
          </details>
        </div>
      )}
      {tab === 'improve' && <ImproveTab insights={insights.data} loading={insights.isLoading} />}
    </>
  )
  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>AI 사용</h1>
          <p>Claude Code·Codex 세션이 크레딧·토큰을 모델별로 얼마나 썼는지, 시간이 어디서 새는지 보고, 규칙을 바꾼 뒤 실제로 나아졌는지 기록해요.</p>
        </div>
        {tab !== 'improve' && scopeSwitch}
      </div>
      {body}
    </div>
  )
}

type Tab = 'usage' | 'pattern' | 'improve'

const TABS: { key: Tab; name: string; tip: string }[] = [
  { key: 'usage', name: '사용량', tip: '크레딧·토큰을 모델별로 얼마나 썼나' },
  { key: 'pattern', name: '작업 패턴', tip: '시간이 어디서 새나 — 지표·시간 분해·병목' },
  { key: 'improve', name: '개선', tip: '개선 제안 → md 에 도입 → 도입 전후 비교' },
]

/** 이번 기간에 눈에 띄는 것 — 지난 기간보다 10% 넘게 나빠진 지표 먼저, 모자라면 좋아진 것, 최대 3개 */
function Highlights({ observer }: { observer: Observer }) {
  const rows = observer.metricDefs
    .filter((d) => d.lowerIsBetter)
    .map((d) => {
      const cur = observer.current.metrics[d.key]
      const prev = observer.previous.metrics[d.key]
      const change = cur != null && prev != null && prev !== 0 ? (cur - prev) / Math.abs(prev) : null
      return { d, cur, prev, change }
    })
    .filter((r) => r.change != null && Math.abs(r.change) >= 0.1)
  const worse = rows.filter((r) => r.change! > 0).sort((a, b) => b.change! - a.change!)
  const better = rows.filter((r) => r.change! < 0).sort((a, b) => a.change! - b.change!)
  const top = [...worse, ...better].slice(0, 3)
  return (
    <section className="card ob-card ob-highlights">
      <div className="ob-head">
        <h3>{observer.label} 눈에 띄는 것</h3>
        <span className="subtle">{PREV_PERIOD[observer.kind]}보다 10% 넘게 달라진 지표</span>
      </div>
      {top.length === 0 ? (
        <p className="subtle ob-empty">{PREV_PERIOD[observer.kind]}와 비교해 크게 달라진 지표가 없어요.</p>
      ) : (
        <ul className="ob-hl">
          {top.map(({ d, cur, prev, change }) => (
            <li key={d.key} className={change! > 0 ? 'bad' : 'good'} title={d.description}>
              <b>{change! > 0 ? '나빠짐' : '좋아짐'}</b>
              <span>{d.label}</span>
              <span className="num">
                {fmtMetric(d, prev)} → {fmtMetric(d, cur)} ({change! > 0 ? '+' : ''}
                {Math.round(change! * 100)}%)
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
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
              {PREV_PERIOD[observer.kind]} {fmtMetric(def, prev)}
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
        <span className="subtle">
          {observer.label} {fmtUsd(observer.current.counts.costUsd)} (API 환산)
        </span>
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

// 개선은 기간이 아니라 세션 단위 — 아직 안 본 세션(이어진 부분 포함)에서 찾고, 단계별로 한 무리씩 본다

type Stage = 'proposed' | 'adopted' | 'done' | 'rejected'
const STAGES: { key: Stage; name: string; tip: string }[] = [
  { key: 'proposed', name: '제안', tip: '도입하면 md 에 들어가고 측정이 시작돼요' },
  { key: 'adopted', name: '측정 중', tip: '도입 직전 14일과 도입 후 14일을 같은 지표로 비교해요' },
  { key: 'done', name: '효과 확인', tip: '측정이 끝난 규칙 — 효과가 없으면 빼세요' },
  { key: 'rejected', name: '기각', tip: '' },
]

function useInsightMutation<T>(fn: (arg: T) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['insights'] })
      qc.invalidateQueries({ queryKey: ['observer'] })
      qc.invalidateQueries({ queryKey: ['rule-load'] })
      qc.invalidateQueries({ queryKey: ['outcomes'] })
    },
  })
}

function ImproveTab({ insights, loading }: { insights: Insight[] | undefined; loading: boolean }) {
  const [params, setParams] = useSearchParams()
  const defs = useQuery({ queryKey: ['metric-defs'], queryFn: () => api.metricDefs(), staleTime: Infinity })
  const targets = useQuery({ queryKey: ['insight-targets'], queryFn: () => api.insightTargets() })
  const create = useInsightMutation(() => api.createInsight({ title: '새 개선안', target: 'global' }, periodKeyOf('week')))
  const [target, setTarget] = useState<string | null>(null)
  const list = insights ?? []
  const count = (st: Stage) => list.filter((i) => i.status === st).length
  const asked = params.get('st') as Stage | null
  const stage: Stage = asked && STAGES.some((s) => s.key === asked) ? asked : count('proposed') > 0 || count('adopted') === 0 ? 'proposed' : 'adopted'
  const setStage = (st: Stage) => {
    const next = new URLSearchParams(params)
    next.set('st', st)
    setParams(next, { replace: true })
    setTarget(null)
  }
  const inStage = list.filter((i) => i.status === stage)
  const targetKeys = [...new Set(inStage.map((i) => i.target ?? ''))].filter(Boolean)
  const items = inStage.filter((i) => !target || i.target === target)
  const tlist = targets.data ?? []
  const mustLeft = list.filter((i) => i.status === 'proposed' && tierOf(i) === 'must').length
  // 화면 = 제안 · 효과 · 읽히는 양. 처음엔 제안, 남은 제안이 없고 도입한 규칙이 있으면 효과
  const askedView = params.get('view') as ImproveView | null
  const view: ImproveView =
    askedView && VIEWS.includes(askedView) ? askedView : count('proposed') === 0 && count('adopted') + count('done') > 0 ? 'effect' : 'proposals'
  const goView = (v: ImproveView) => {
    const next = new URLSearchParams(params)
    next.set('view', v)
    ;['st', 'tg', 'ins'].forEach((k) => next.delete(k))
    setParams(next, { replace: true })
  }

  return (
    <div className="ob">
      <ImproveTiles view={view} onPick={goView} insights={list} />
      {view === 'effect' && <OutcomeCard defs={defs.data ?? []} />}
      {view === 'load' && <RuleLoadCard />}
      {view === 'proposals' && (
        <>
      <ScanBar />
      <div className="ob-stage-bar">
        <div className="segmented" role="tablist" aria-label="개선 단계">
          {STAGES.map((st) => {
            const n = count(st.key)
            return (
              <button key={st.key} role="tab" aria-selected={stage === st.key} className={stage === st.key ? 'on' : ''} onClick={() => setStage(st.key)} title={st.tip}>
                {st.name}
                <span className={`ob-stage-n${st.key === 'proposed' && mustLeft > 0 ? ' must' : ''}`}>{n}</span>
              </button>
            )
          })}
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => create.mutate(undefined)} disabled={create.isPending}>
          + 직접 추가
        </button>
      </div>
      {targetKeys.length > 1 && (
        <div className="ob-target-chips">
          <span className="subtle">넣을 곳</span>
          {[null, ...targetKeys].map((k) => (
            <button key={k ?? 'all'} className={`chip${target === k ? ' on' : ''}`} onClick={() => setTarget(k)}>
              {k ? (tlist.find((t) => t.key === k)?.label ?? k) : '전체'}
              <span className="count">{k ? inStage.filter((i) => i.target === k).length : inStage.length}</span>
            </button>
          ))}
        </div>
      )}
      {loading && <Loading />}
      {!loading && items.length === 0 && <div className="ob-note">{EMPTY[stage]}</div>}
      {items.length > 0 && stage === 'proposed' && <ProposedByTier items={items} defs={defs.data ?? []} targets={tlist} />}
      {items.length > 0 && stage === 'done' && <DoneByVerdict items={items} defs={defs.data ?? []} targets={tlist} />}
      {items.length > 0 && (stage === 'adopted' || stage === 'rejected') && (
        <div className="ob-list">
          {items.map((i) => (
            <InsightCard key={i.id} insight={i} defs={defs.data ?? []} targets={tlist} />
          ))}
        </div>
      )}
        </>
      )}
    </div>
  )
}

type ImproveView = 'proposals' | 'effect' | 'load'
const VIEWS: ImproveView[] = ['proposals', 'effect', 'load']

/** 재발 합계 — 도입 후 작업이 충분히 쌓인 규칙만 */
function summarizeOutcomes(rules: Outcome[]) {
  const ready = rules.filter((r) => r.afterHours >= MIN_AFTER_HOURS)
  const sum = (f: (r: Outcome) => number) => ready.reduce((s, r) => s + f(r), 0)
  const bT = sum((r) => r.beforeHours)
  const aT = sum((r) => r.afterHours)
  const bRate = bT >= 1 ? (10 * sum((r) => r.beforeHits)) / bT : null
  const aRate = aT >= 1 ? (10 * sum((r) => r.afterHits)) / aT : null
  const judged = rules.filter((r) => r.verdict === '개선' || r.verdict === '변화 없음' || r.verdict === '악화')
  return {
    ready,
    bRate,
    aRate,
    pct: changePct(bRate, aRate),
    judged,
    better: judged.filter((r) => r.verdict === '개선').length,
    worse: judged.filter((r) => r.verdict === '악화').length,
    minAfter: rules.length ? Math.min(...rules.map((r) => r.afterHours)) : 0,
    /** 가장 먼저 결과가 나올 규칙까지 남은 작업 시간 */
    leftHours: rules.length ? Math.max(0, MIN_AFTER_HOURS - Math.max(...rules.map((r) => r.afterHours))) : 0,
    misplaced: rules.filter((r) => r.repo && r.sourceRepos.length > 0 && !r.sourceRepos.includes(r.repo)).length,
  }
}

const fmtTok = (n: number) => (n >= 10000 ? `${(n / 10000).toFixed(1)}만` : n.toLocaleString('en-US'))

/** 개선 탭 요약 타일 — 세 화면의 핵심 숫자를 늘 위에 두고, 누르면 그 화면으로 */
function ImproveTiles({ view, onPick, insights }: { view: ImproveView; onPick: (v: ImproveView) => void; insights: Insight[] }) {
  const pending = useQuery({ queryKey: ['insight-pending'], queryFn: () => api.insightPending(), refetchInterval: 60_000 })
  const outcomes = useQuery({ queryKey: ['outcomes'], queryFn: () => api.outcomes(), staleTime: 60_000 })
  const load = useRuleLoad()
  const proposed = insights.filter((i) => i.status === 'proposed').length
  const must = insights.filter((i) => i.status === 'proposed' && tierOf(i) === 'must').length
  const unseen = pending.data?.sessions.length ?? 0
  const rules = outcomes.data?.rules ?? []
  const missing = outcomes.data?.missingSignals ?? 0
  const o = summarizeOutcomes(rules)
  const rows = (load.data ?? []).filter((r) => r.chars > 0)
  const biggest = rows.reduce<RuleLoad | null>((m, r) => (!m || r.estTokens > m.estTokens ? r : m), null)
  const over = rows.filter((r) => r.vitaRules >= r.budget).length
  const diet = rows.reduce((s, r) => s + r.dietPending, 0)
  const longLines = rows.reduce((s, r) => s + r.longLines, 0)

  const effectTone = o.pct != null && o.pct > 0 ? 'bad' : o.worse > 0 ? 'bad' : o.misplaced > 0 ? 'warn' : o.pct != null && o.pct < 0 ? 'good' : ''
  const tiles: { key: ImproveView; title: string; big: string; sub: string; tone: string }[] = [
    {
      key: 'proposals',
      title: '개선 제안',
      big: `${proposed}개`,
      sub: [must > 0 ? `필수 ${must} 남음` : '필수 없음', unseen > 0 ? `안 본 세션 ${unseen}` : ''].filter(Boolean).join(' · '),
      tone: must > 0 ? 'bad' : '',
    },
    {
      key: 'effect',
      title: '도입 효과',
      big:
        rules.length === 0 && missing === 0
          ? '—'
          : o.ready.length === 0
            ? '측정 중'
            : o.pct == null
              ? '비교 불가'
              : `${o.pct > 0 ? '+' : ''}${o.pct}%`,
      sub:
        rules.length === 0 && missing === 0
          ? '도입한 규칙이 없어요'
          : [
              o.ready.length === 0 && rules.length > 0 ? `결과까지 작업 ${o.leftHours.toFixed(1)}시간 남음` : '',
              o.judged.length > 0 ? `지표 좋아짐 ${o.better}/${o.judged.length}` : '',
              o.worse > 0 ? `나빠짐 ${o.worse}` : '',
              o.misplaced > 0 ? `넣은 곳이 다른 규칙 ${o.misplaced}` : '',
              missing > 0 ? `신호 없음 ${missing}` : '',
            ]
              .filter(Boolean)
              .join(' · '),
      tone: effectTone,
    },
    {
      key: 'load',
      title: '매 세션 읽히는 양',
      big: biggest ? `${fmtTok(biggest.estTokens)}토큰` : '—',
      sub: [
        biggest ? `가장 큰 곳 ${biggest.label.replace(/ \(.*\)$/, '')}` : '',
        diet > 0 ? `줄이기 ${diet}줄 대기` : longLines > 0 ? `긴 색인 줄 ${longLines}` : '',
        over > 0 ? `상한 ${over}곳` : '',
      ]
        .filter(Boolean)
        .join(' · '),
      tone: over > 0 ? 'bad' : diet > 0 || longLines > 0 ? 'warn' : '',
    },
  ]
  return (
    <div className="ob-tiles" role="tablist" aria-label="개선 화면">
      {tiles.map((t) => (
        <button
          key={t.key}
          role="tab"
          aria-selected={view === t.key}
          className={`ob-tile${view === t.key ? ' on' : ''}${t.tone ? ` tone-${t.tone}` : ''}`}
          onClick={() => onPick(t.key)}
        >
          <span className="ob-tile-title">{t.title}</span>
          <b className="ob-tile-big">{t.big}</b>
          <span className="ob-tile-sub">{t.sub}</span>
        </button>
      ))}
    </div>
  )
}

const EMPTY: Record<Stage, string> = {
  proposed: '남은 제안이 없어요. 위의 ‘찾기’로 새 세션에서 개선점을 찾거나, 직접 추가하세요.',
  adopted: '측정 중인 규칙이 없어요. 제안을 도입하면 여기서 도입 전후를 비교해요.',
  done: '측정이 끝난 규칙이 아직 없어요.',
  rejected: '기각한 제안이 없어요.',
}

/** 도입 후 작업이 이만큼은 쌓여야 10시간당 재발을 비교한다(그 전엔 한두 번에 크게 흔들림) */
const MIN_AFTER_HOURS = 5
const rateText = (v: number | null) => (v == null ? '–' : v < 10 ? v.toFixed(1) : String(Math.round(v)))
const changePct = (b: number | null, a: number | null) => (b == null || a == null || b === 0 ? null : Math.round((100 * (a - b)) / b))
const md = (ymdStr: string) => `${Number(ymdStr.slice(5, 7))}/${Number(ymdStr.slice(8, 10))}`

/** 도입 효과 — 규칙을 넣은 뒤 같은 실수가 얼마나 줄었나(작업 10시간당 재발)와 병목 지표가 어떻게 바뀌었나 */
function OutcomeCard({ defs }: { defs: MetricDef[] }) {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['outcomes'], queryFn: () => api.outcomes(), staleTime: 60_000 })
  const running = useRunningJob('observer:signals')
  const last = useLastJob('observer:signals')
  const start = useStartJob(() => api.insightSignals())
  const doneId = last?.status === 'DONE' ? last.id : null
  useEffect(() => {
    if (doneId) {
      qc.invalidateQueries({ queryKey: ['outcomes'] })
      qc.invalidateQueries({ queryKey: ['insights'] })
    }
  }, [doneId, qc])
  const rules = q.data?.rules ?? []
  const missing = q.data?.missingSignals ?? 0
  const { ready, bRate, aRate, pct, leftHours, judged, better } = summarizeOutcomes(rules)
  if (rules.length === 0 && missing === 0) {
    return <div className="ob-note">도입한 규칙이 없어요. 제안을 도입하면 여기서 같은 실수가 얼마나 줄었는지 보여줘요.</div>
  }
  return (
    <section className="ob-outcome">
      <div className="ob-outcome-head">
        <b>도입 효과</b>
        <span className="ob-outcome-sum">
          {ready.length > 0 ? (
            <>
              같은 실수 {rateText(bRate)} → {rateText(aRate)}회/10시간
              {pct != null && <b className={pct < 0 ? 'good' : pct > 0 ? 'bad' : ''}> {pct > 0 ? `+${pct}` : pct}%</b>}
            </>
          ) : (
            <>측정 중 — 규칙을 넣은 뒤 작업이 {MIN_AFTER_HOURS}시간 쌓이면 결과가 나와요(결과까지 {leftHours.toFixed(1)}시간 남음)</>
          )}
          {judged.length > 0 && ` · 지표 좋아짐 ${better}/${judged.length}`}
        </span>
        {missing > 0 && (
          <button className="btn btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running || start.isPending}>
            {running ? (running.message ?? '고르는 중') : `신호 없는 규칙 ${missing}개 — 재발 신호 만들기`}
          </button>
        )}
      </div>
      <details className="ob-how">
        <summary>어떻게 재나요?</summary>
        <ul>
          <li>
            <b>줄이려는 실수</b> — 규칙마다 정한 «재발 신호»(오류 문구·거절된 명령·정정 요청에 나오는 말)가 나온 횟수. 도입 전 28일과 도입 후(최대 28일)를 작업
            10시간당으로 맞춰 비교해요. 도입 후 작업이 {MIN_AFTER_HOURS}시간 쌓이기 전엔 «측정 중».
          </li>
          <li>
            <b>병목 지표</b> — 제안할 때 고른 지표 하나(도구 실패율·정정 요청 비율 등)를 도입 직전 14일과 도입 후 14일로 비교. 10% 넘게 좋아지면 «좋아짐».
          </li>
          <li>
            <b>늘어난 읽기</b> — 이 규칙 때문에 매 세션 더 읽히는 양(글자로 어림한 토큰). 메모리 규칙은 색인 한 줄만 늘 읽혀요.
          </li>
          <li>
            <b>범위</b> — 규칙을 넣은 저장소의 세션만 셉니다(그 저장소 세션만 규칙을 읽으므로). 전역 규칙은 전체.
          </li>
        </ul>
      </details>
      <div className="ob-score">
        <div className="ob-score-row ob-score-headrow" aria-hidden>
          <span>규칙</span>
          <span>줄이려는 실수</span>
          <span>병목 지표</span>
          <span>늘어난 읽기</span>
          <span>결과</span>
        </div>
        {rules.map((o) => (
          <OutcomeRow key={o.id} o={o} def={defs.find((d) => d.key === o.metric)} />
        ))}
      </div>
    </section>
  )
}

/** 규칙 하나 = 한 줄 성적표(실수 전→후 · 지표 전→후 · 늘어난 읽기 · 결과). 누르면 주별 막대와 재는 방법 */
function OutcomeRow({ o, def }: { o: Outcome; def: MetricDef | undefined }) {
  const [open, setOpen] = useState(false)
  const early = o.afterHours < MIN_AFTER_HOURS
  const pct = early ? null : changePct(o.beforeRate, o.afterRate)
  const misplaced = !!o.repo && o.sourceRepos.length > 0 && !o.sourceRepos.includes(o.repo)
  const result: { text: string; tone: string } = early
    ? { text: `결과까지 ${Math.max(0, MIN_AFTER_HOURS - o.afterHours).toFixed(1)}h`, tone: 'wait' }
    : pct == null
      ? { text: o.beforeHits === 0 && o.afterHits === 0 ? '재발 없음' : '비교 불가', tone: 'flat' }
      : pct <= -10
        ? { text: `줄었음 ${pct}%`, tone: 'good' }
        : pct >= 10
          ? { text: `늘었음 +${pct}%`, tone: 'bad' }
          : { text: '변화 없음', tone: 'flat' }
  return (
    <div className={`ob-score-item${open ? ' open' : ''}`}>
      <button className="ob-score-row" onClick={() => setOpen((v) => !v)} aria-expanded={open}>
        <span className="ob-score-rule">
          <b>{o.title}</b>
          {o.effect && <span className="subtle">{o.effect}</span>}
        </span>
        <span className="ob-score-cell">
          <span className="ob-score-k">줄이려는 실수</span>
          <b>
            {o.beforeHits}회 → {o.afterHits}회
          </b>
          <span className="subtle">
            도입 전 {o.beforeHours.toFixed(0)}h · 후 {o.afterHours.toFixed(1)}h 작업
          </span>
        </span>
        <span className="ob-score-cell">
          <span className="ob-score-k">{def?.label ?? '병목 지표'}</span>
          {o.metric ? (
            <>
              <b>
                {fmtMetric(def, o.baseline)} → {fmtMetric(def, o.after)}
              </b>
              <span className="subtle">
                {def?.label ?? o.metric} · {o.verdict ?? '측정 중'}
              </span>
            </>
          ) : (
            <span className="subtle">지표 없음</span>
          )}
        </span>
        <span className="ob-score-cell">
          <span className="ob-score-k">늘어난 읽기</span>
          <b>+{tok(o.loadTokens)}</b>
          <span className="subtle">매 세션</span>
        </span>
        <span className="ob-score-result">
          <span className={`ob-verdict ob-row-verdict ${result.tone}`}>{result.text}</span>
          {misplaced && (
            <span className="ob-score-warn" title={`문제는 ${o.sourceRepos.join(', ')} 세션에서 났는데 ${o.repo} 에 넣었어요`}>
              ! 넣은 곳이 다름
            </span>
          )}
          <Icon name="chevron" size={12} />
        </span>
      </button>
      {open && <OutcomeDetail o={o} misplaced={misplaced} />}
    </div>
  )
}

/** 펼친 줄 — 주별 재발 막대 + 재는 범위·기간·신호 + 경고 */
function OutcomeDetail({ o, misplaced }: { o: Outcome; misplaced: boolean }) {
  const [hover, setHover] = useState<number | null>(null)
  const n = o.weeks.length
  const W = 300
  const H = 64
  const top = 14
  const band = W / Math.max(1, n)
  const bw = Math.min(14, band * 0.6)
  const max = Math.max(1, ...o.weeks.map((w) => w.hits))
  const adoptIdx = o.weeks.findIndex((w) => w.week === o.adoptedWeek)
  const w = hover != null ? o.weeks[hover] : null
  return (
    <div className="ob-score-detail">
      <div className="ob-outcome-chart">
        <div className="ob-score-k">주별 재발 횟수</div>
        <svg viewBox={`0 0 ${W} ${H}`} width="100%" height={H} role="img" aria-label={`${o.title} 주별 재발 횟수`} onPointerLeave={() => setHover(null)}>
          <line x1={0} x2={W} y1={H - 0.5} y2={H - 0.5} className="ob-axis" />
          {adoptIdx >= 0 && (
            <>
              <line x1={adoptIdx * band} x2={adoptIdx * band} y1={top - 4} y2={H} className="ob-adopt-line" />
              <text x={adoptIdx * band + 3} y={top - 5} className="ob-adopt-label">
                도입
              </text>
            </>
          )}
          {o.weeks.map((wk, i) => {
            const h = wk.hits === 0 ? 0 : Math.max(3, ((H - top) * wk.hits) / max)
            const x = i * band + (band - bw) / 2
            const y = H - h
            const r = Math.min(4, h, bw / 2)
            return (
              <g key={wk.week} onPointerEnter={() => setHover(i)} onFocus={() => setHover(i)} tabIndex={0} className={hover === i ? 'on' : ''}>
                <rect x={i * band} y={0} width={band} height={H} fill="transparent" />
                {h > 0 && (
                  <path
                    className="ob-bar"
                    d={`M${x},${H} L${x},${y + r} Q${x},${y} ${x + r},${y} L${x + bw - r},${y} Q${x + bw},${y} ${x + bw},${y + r} L${x + bw},${H} Z`}
                  />
                )}
              </g>
            )
          })}
        </svg>
        <div className="ob-outcome-read subtle">
          {w ? (
            <>
              <b>{w.hits}회</b> · {md(w.from)} 주 · 작업 {fmtShort(w.activeSec)}
            </>
          ) : (
            <>{md(o.weeks[0]?.from ?? '')}부터 · 막대에 올리면 그 주 숫자</>
          )}
        </div>
      </div>
      <dl className="ob-score-how">
        <dt>범위</dt>
        <dd>{o.repo ? `${o.repo} 세션만` : '전체 세션'}</dd>
        <dt>기간</dt>
        <dd>
          도입 전 28일 {o.beforeHits}회/{o.beforeHours.toFixed(0)}h → 도입 후 {o.afterHits}회/{o.afterHours.toFixed(1)}h
        </dd>
        <dt>재발 신호</dt>
        <dd className="mono">{o.signals.join(' · ')}</dd>
        <dt>넣은 곳</dt>
        <dd>
          {o.targetLabel ?? o.target}
          {misplaced && <span className="bad"> — 문제는 {o.sourceRepos.join(', ')} 세션에서 났어요. 그 세션은 이 규칙을 안 읽어요</span>}
        </dd>
      </dl>
      <Link className="ob-score-link" to={`/ai?tab=improve&view=proposals&st=${o.status}&ins=${o.id}`}>
        규칙 카드 열기(신호·문안 고치기)
      </Link>
    </div>
  )
}

const tok = (n: number) => `${n.toLocaleString('en-US')}토큰`
const useRuleLoad = () => useQuery({ queryKey: ['rule-load'], queryFn: () => api.ruleLoad(), staleTime: 30_000 })

/** 매 세션 읽히는 양 — md·메모리 색인은 세션을 열 때마다 통째로 읽힌다. 메모리 색인은 줄이기 제안까지 */
function RuleLoadCard() {
  const load = useRuleLoad()
  const [params] = useSearchParams()
  const focus = params.get('tg')
  const [open, setOpen] = useState<string | null>(null)
  const focusRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (focus && load.data) focusRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [focus, load.data])
  const rows = (load.data ?? []).filter((r) => r.chars > 0)
  if (rows.length === 0) return null
  const max = Math.max(...rows.map((r) => r.estTokens))
  return (
    <section className="ob-load">
      <div className="ob-load-head">
        <b>매 세션 읽히는 양</b>
        <span className="subtle">
          아래 파일은 세션을 열 때마다 통째로 읽혀요. 메모리는 색인(MEMORY.md)만 늘 읽히고, 링크된 문서는 관련 있을 때만 열려요. 토큰은 글자로 어림한
          추정이에요.
        </span>
      </div>
      {rows.map((r) => {
        const over = r.vitaRules >= r.budget
        return (
          <div key={r.target} className={`ob-load-item${focus === r.target ? ' focus' : ''}`} ref={focus === r.target ? focusRef : undefined}>
            <div className="ob-load-row">
              <span className="ob-load-name" title={r.file}>
                {r.label}
              </span>
              <span className="ob-load-bar" title={`Vita 규칙 ${tok(Math.round((r.estTokens * r.vitaChars) / Math.max(1, r.chars)))} 포함`}>
                <span style={{ width: `${(100 * r.estTokens) / max}%` }}>
                  <i style={{ width: `${(100 * r.vitaChars) / Math.max(1, r.chars)}%` }} />
                </span>
              </span>
              <span className="ob-load-num">
                약 {tok(r.estTokens)} <span className="subtle">· {r.lines}줄</span>
              </span>
              <span className={`ob-load-vita${over ? ' over' : ''}`} title={over ? '상한 — 새로 넣기 전에 효과 없는 규칙을 빼거나 합치세요' : 'Vita 가 넣은 규칙 / 권장 상한'}>
                Vita 규칙 {r.vitaRules}/{r.budget}
              </span>
              {r.kind === 'memory' && (r.longLines > 0 || r.dietPending > 0) ? (
                <button className={`btn btn-sm${open === r.target ? ' btn-primary' : ''}`} onClick={() => setOpen(open === r.target ? null : r.target)}>
                  {r.dietPending > 0 ? `줄이기 제안 ${r.dietPending}줄` : `긴 색인 줄 ${r.longLines}개 줄이기`}
                </button>
              ) : (
                <span />
              )}
            </div>
            {open === r.target && <DietPanel load={r} />}
          </div>
        )
      })}
    </section>
  )
}

/** 메모리 색인 줄이기 — 긴 줄을 짧은 단서로, 빠지는 세부는 링크 문서로 옮긴다. 고른 줄만 적용, 쓰기 전 백업 */
function DietPanel({ load: r }: { load: RuleLoad }) {
  const qc = useQueryClient()
  const items = useQuery({ queryKey: ['diet', r.target], queryFn: () => api.diet(r.target) })
  const log = useQuery({ queryKey: ['diet-log', r.target], queryFn: () => api.dietLog(r.target) })
  const jobKey = `observer:diet:${r.target}`
  const running = useRunningJob(jobKey)
  const last = useLastJob(jobKey)
  const start = useStartJob(() => api.dietPropose(r.target))
  const [off, setOff] = useState<Set<number>>(new Set())
  const [confirm, setConfirm] = useState(false)
  const refresh = () => {
    qc.invalidateQueries({ queryKey: ['diet', r.target] })
    qc.invalidateQueries({ queryKey: ['diet-log', r.target] })
    qc.invalidateQueries({ queryKey: ['rule-load'] })
  }
  const apply = useMutation({ mutationFn: (ids: number[]) => api.dietApply(r.target, ids), onSuccess: refresh })
  const dismiss = useMutation({ mutationFn: (id: number) => api.dietDismiss(id), onSuccess: refresh })
  const doneId = last?.status === 'DONE' ? last.id : null
  useEffect(() => {
    if (doneId) refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [doneId])
  const list = items.data ?? []
  const chosen = list.filter((d) => !off.has(d.id))
  const saveTok = chosen.reduce((s, d) => s + d.savedTokens, 0)
  const after = r.estTokens - saveTok
  const pct = r.estTokens > 0 ? Math.round((100 * saveTok) / r.estTokens) : 0
  return (
    <div className="ob-diet">
      {(log.data ?? []).length > 0 && (
        <div className="ob-diet-log">
          {(log.data ?? []).map((l) => (
            <span key={l.appliedAt}>
              {fmtDateTime(l.appliedAt)} 정리 {l.lines}줄 · 색인 {tok(l.beforeTokens)} → {tok(l.afterTokens)}{' '}
              <b className="good">-{Math.round((100 * (l.beforeTokens - l.afterTokens)) / Math.max(1, l.beforeTokens))}%</b>
            </span>
          ))}
        </div>
      )}
      {list.length === 0 ? (
        <div className="ob-diet-empty">
          <span className="subtle">
            150자 넘는 색인 줄 {r.longLines}개를 짧은 단서로 줄이는 정리안을 만들어요. 줄에서 빠지는 세부 중 링크 문서에 없는 건 그 문서 끝으로 옮겨요.
          </span>
          <button className="btn btn-primary btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running || start.isPending}>
            <Icon name="sparkle" size={13} />
            {running ? (running.message ?? '만드는 중') : '줄이기 제안 만들기'}
          </button>
          {!running && last?.status === 'FAILED' && <span className="bad">{last.message}</span>}
          {!running && last?.status === 'DONE' && <span className="subtle">{last.message}</span>}
        </div>
      ) : (
        <>
          <div className="ob-diet-sum">
            <span>
              고른 {chosen.length}줄 적용하면 색인 <b>{tok(r.estTokens)}</b> → <b>{tok(after)}</b> <b className="good">-{pct}%</b>
              <span className="subtle"> · 매 세션 약 {tok(saveTok)} 덜 읽음</span>
            </span>
            {confirm ? (
              <span className="ob-confirm">
                <span className="subtle">{r.file} 을 백업한 뒤 고쳐요</span>
                <button
                  className="btn btn-primary btn-sm"
                  onClick={() => {
                    apply.mutate(chosen.map((d) => d.id))
                    setConfirm(false)
                  }}
                >
                  적용
                </button>
                <button className="btn btn-ghost btn-sm" onClick={() => setConfirm(false)}>
                  취소
                </button>
              </span>
            ) : (
              <span className="ob-confirm">
                <button className="btn btn-primary btn-sm" disabled={chosen.length === 0 || apply.isPending} onClick={() => setConfirm(true)}>
                  고른 {chosen.length}줄 적용
                </button>
                <button className="btn btn-ghost btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running}>
                  {running ? (running.message ?? '만드는 중') : '다시 만들기'}
                </button>
              </span>
            )}
          </div>
          {apply.data && <div className="subtle ob-diet-msg">{apply.data.message}</div>}
          {apply.isError && <div className="bad ob-diet-msg">{(apply.error as Error).message}</div>}
          <ul className="ob-diet-list">
            {list.map((d) => (
              <DietRow
                key={d.id}
                item={d}
                on={!off.has(d.id)}
                toggle={() =>
                  setOff((cur) => {
                    const n = new Set(cur)
                    if (n.has(d.id)) n.delete(d.id)
                    else n.add(d.id)
                    return n
                  })
                }
                dismiss={() => dismiss.mutate(d.id)}
              />
            ))}
          </ul>
        </>
      )}
    </div>
  )
}

function DietRow({ item: d, on, toggle, dismiss }: { item: DietItem; on: boolean; toggle: () => void; dismiss: () => void }) {
  return (
    <li className={on ? '' : 'off'}>
      <input type="checkbox" checked={on} onChange={toggle} aria-label="이 줄 적용" />
      <div className="ob-diet-body">
        <div className="ob-diet-old">{d.oldLine}</div>
        <div className="ob-diet-new">{d.newLine}</div>
        {d.moved && <div className="ob-diet-moved">→ {d.file} 끝으로 옮김: {d.moved}</div>}
        {d.reason && <div className="subtle ob-diet-reason">{d.reason}</div>}
      </div>
      <span className="ob-diet-save good">-{tok(d.savedTokens)}</span>
      <button className="btn btn-ghost btn-sm" onClick={dismiss} title="이 줄은 그대로 둬요">
        빼기
      </button>
    </li>
  )
}

/** 이 규칙이 늘리는 읽기 양 — 제안이면 «넣으면 +N», 도입했으면 «매 세션 N» */
function LoadChip({ insight: i, over }: { insight: Insight; over: boolean }) {
  const l = i.load
  const proposed = i.status === 'proposed' || i.status === 'rejected'
  const pct = l.targetTokens > 0 ? (100 * l.always) / l.targetTokens : null
  const tip = proposed
    ? `넣으면 ${i.targetLabel ?? '넣을 곳'} 이 매 세션 약 ${tok(l.targetTokens)} → ${tok(l.targetTokens + l.always)}` +
      (pct != null ? ` (+${pct.toFixed(1)}%)` : '') +
      (l.onDemand > 0 ? `\n메모리 본문 약 ${tok(l.onDemand)}은 관련 있을 때만 읽혀요` : '') +
      (over ? '\n이 곳은 Vita 규칙 상한이에요 — 효과 없는 규칙부터 빼는 걸 권해요' : '')
    : `이 규칙이 매 세션 약 ${tok(l.always)} 읽혀요` + (l.onDemand > 0 ? ` + 관련 있을 때 본문 ${tok(l.onDemand)}` : '')
  return (
    <Link className={`ob-load-chip${over && proposed ? ' over' : ''}`} title={`${tip}\n누르면 읽히는 양 화면`} to={`/ai?tab=improve&view=load&tg=${encodeURIComponent(i.target ?? '')}`}>
      {proposed ? `+${tok(l.always)}/세션` : `${tok(l.always)}/세션`}
      {over && proposed && ' · 상한'}
    </Link>
  )
}

/** 넣을 곳별 상한 여부 */
function useOverBudget(): (target: string | null) => boolean {
  const load = useRuleLoad()
  return (t) => !!t && (load.data ?? []).some((r) => r.target === t && r.vitaRules >= r.budget)
}

/** 아직 안 본 세션 — 새 세션과, 봤던 뒤로 내용이 이어진 세션(이어진 부분만 본다) */
function ScanBar() {
  const qc = useQueryClient()
  const pending = useQuery({ queryKey: ['insight-pending'], queryFn: () => api.insightPending(), refetchInterval: 60_000 })
  const running = useRunningJob('observer:scan')
  const last = useLastJob('observer:scan')
  const start = useStartJob(() => api.scanInsights())
  const doneId = last?.status === 'DONE' ? last.id : null
  useEffect(() => {
    if (doneId) {
      qc.invalidateQueries({ queryKey: ['insights'] })
      qc.invalidateQueries({ queryKey: ['insight-pending'] })
    }
  }, [doneId, qc])
  const ss = pending.data?.sessions ?? []
  const rescan = ss.filter((s) => s.rescan).length
  const signal = ss.filter((s) => s.errors + s.corrections > 0).length
  return (
    <div className="ob-scan">
      <div className="ob-scan-text">
        <b>{pending.isLoading ? '세션 확인 중' : ss.length > 0 ? `아직 안 본 세션 ${ss.length}개` : '새로 볼 세션이 없어요'}</b>
        <span className="subtle">
          {ss.length > 0
            ? [rescan > 0 && `이어진 세션 ${rescan}개는 이어진 부분만`, signal > 0 && `실패·정정이 있었던 세션 ${signal}개`].filter(Boolean).join(' · ')
            : '세션마다 어디까지 봤는지 남겨서, 새로 생긴 기록만 봐요'}
        </span>
        {ss.length > 0 && (
          <details className="ob-scan-list">
            <summary>목록</summary>
            <ul>
              {ss.map((x) => (
                <li key={x.sessionId}>
                  <Link to={`/sessions/${x.taskKey}`}>{x.title}</Link>
                  {x.rescan && <span className="ob-rescan">이어짐</span>}
                  <span className="subtle">
                    요청 {x.prompts}
                    {x.errors > 0 && ` · 실패 ${x.errors}`}
                    {x.corrections > 0 && ` · 정정 ${x.corrections}`}
                  </span>
                </li>
              ))}
            </ul>
          </details>
        )}
      </div>
      <div className="ob-propose">
        {last?.status === 'FAILED' && !running && <span className="bad" title={last.message ?? ''}>찾기 실패</span>}
        {last?.status === 'DONE' && !running && <span className="subtle">{last.message}</span>}
        <button className="btn btn-primary btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running || start.isPending || ss.length === 0}>
          <Icon name="sparkle" size={13} />
          {running ? (running.message ?? '찾는 중') : ss.length > 0 ? `${ss.length}개 세션에서 개선점 찾기` : '개선점 찾기'}
        </button>
      </div>
    </div>
  )
}

const VERDICT_ORDER = ['개선', '변화 없음', '악화', '판단 보류', '측정 불가', '참고 지표']
const VERDICT_NOTE: Record<string, string> = {
  '변화 없음': '효과가 없으면 ‘규칙 빼기’로 md 에서 빼요',
  악화: '효과가 없으면 ‘규칙 빼기’로 md 에서 빼요',
  '판단 보류': '측정 기간에 일한 날이 3일이 안 돼 판단하지 못했어요 — 펼쳐서 ‘다시 측정’',
}
/** 숫자로 판정한 결과에만 증감 % 를 붙인다 */
const SHOW_DELTA = new Set(['개선', '변화 없음', '악화'])

/** 효과 확인 — 좋아진 것 / 그대로 / 나빠진 것으로 묶는다. 효과 없는 묶음엔 빼기 권유 */
function DoneByVerdict({ items, defs, targets }: { items: Insight[]; defs: MetricDef[]; targets: InsightTarget[] }) {
  const groups = [...new Set([...VERDICT_ORDER, ...items.map((i) => i.verdict ?? '측정 불가')])]
  return (
    <>
      {groups.map((v) => {
        const list = items.filter((i) => (i.verdict ?? '측정 불가') === v)
        if (list.length === 0) return null
        return (
          <div key={v} className="ob-tier">
            <div className="ob-tier-title">
              {v} <span className="count">{list.length}</span>
              {VERDICT_NOTE[v] && <span className="subtle">{VERDICT_NOTE[v]}</span>}
            </div>
            {list.map((i) => (
              <InsightCard key={i.id} insight={i} defs={defs} targets={targets} />
            ))}
          </div>
        )
      })}
    </>
  )
}

/** 발생 세션 — 이 제안의 근거가 된 세션, 누르면 세션 상세 */
function SourceSessions({ insight: i }: { insight: Insight }) {
  if (i.sessions.length === 0) return null
  return (
    <span className="ob-src">
      발생 세션:{' '}
      {i.sessions.map((s, k) => (
        <span key={s.taskKey}>
          {k > 0 && ', '}
          <Link to={`/sessions/${s.taskKey}`} title={s.repo ? `${s.repo} 저장소` : undefined}>
            {s.title}
          </Link>
        </span>
      ))}
    </span>
  )
}

const VERDICT_TONE: Record<string, string> = { 개선: 'good', 악화: 'bad', '변화 없음': 'flat', '측정 중': 'wait' }

const TIERS: { key: InsightTier; name: string; tip: string }[] = [
  { key: 'must', name: '필수', tip: '실제 사고·권한 거절·같은 정정이 반복됐고, 넣어도 부작용이 거의 없는 규칙 — 고민 없이 넣어도 돼요' },
  { key: 'recommend', name: '권장', tip: '효과는 숫자로 보이지만 특정 저장소·상황에만 해당 — 해당 작업을 자주 하면 넣으세요' },
  { key: 'optional', name: '선택', tip: '작업 방식·취향을 바꾸는 규칙, 상황에 따라 손해일 수 있음 — 2주 실험으로 넣고 효과 없으면 빼세요' },
]
const tierOf = (i: Insight): InsightTier => i.tier ?? 'recommend'

/** 제안을 필수 → 권장 → 선택으로 묶고, 필수는 한 번에 도입. 묶인 예전 제안은 규칙 하나씩으로 나눠 정리 */
function ProposedByTier({ items, defs, targets }: { items: Insight[]; defs: MetricDef[]; targets: InsightTarget[] }) {
  const qc = useQueryClient()
  const [confirmMust, setConfirmMust] = useState(false)
  const must = items.filter((i) => tierOf(i) === 'must' && i.metric && i.target && i.snippet)
  const adoptMust = useInsightMutation(() => api.adoptMustInsights())
  const running = useRunningJob('observer:reclassify')
  const last = useLastJob('observer:reclassify')
  const reclassify = useStartJob(() => api.reclassifyInsights())
  // 정리가 끝나면 목록을 다시 읽는다
  const doneId = last?.status === 'DONE' ? last.id : null
  useEffect(() => {
    if (doneId) qc.invalidateQueries({ queryKey: ['insights'] })
  }, [doneId, qc])
  const needsSplit = items.some((i) => !i.tier || (i.snippet ?? '').trim().split('\n').length > 2)
  return (
    <>
      <div className="ob-tier-bar">
        {confirmMust ? (
          <>
            <span>
              필수 {must.length}개를 각 md 에 넣고 측정을 시작할까요? 매 세션 합계 약 {tok(must.reduce((s, i) => s + i.load.always, 0))} 더 읽혀요.
            </span>
            <button
              className="btn btn-primary btn-sm"
              onClick={() => {
                adoptMust.mutate(undefined)
                setConfirmMust(false)
              }}
            >
              넣고 도입
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirmMust(false)}>
              취소
            </button>
          </>
        ) : (
          <>
            <button className="btn btn-primary btn-sm" disabled={must.length === 0 || adoptMust.isPending} onClick={() => setConfirmMust(true)}>
              필수 {must.length}개 한 번에 도입
            </button>
            <button
              className={`btn btn-sm${needsSplit ? '' : ' btn-ghost'}`}
              disabled={!!running || reclassify.isPending}
              onClick={() => reclassify.mutate(undefined)}
              title="규칙 여러 개가 묶인 제안을 하나씩 나누고 필수·권장·선택을 다시 매겨요(도입 안 한 제안만)"
            >
              {running ? (running.message ?? '나누는 중') : '제안 나눠 정리'}
            </button>
            {!running && last?.status === 'DONE' && <span className="subtle">{last.message}</span>}
            {!running && last?.status === 'FAILED' && <span className="bad">{last.message}</span>}
          </>
        )}
        {adoptMust.isError && <span className="bad">{(adoptMust.error as Error).message}</span>}
      </div>
      {TIERS.map((t) => {
        const list = items.filter((i) => tierOf(i) === t.key)
        if (list.length === 0) return null
        return (
          <div key={t.key} className="ob-tier">
            <div className={`ob-tier-title ${t.key}`} title={t.tip}>
              {t.name} <span className="count">{list.length}</span>
              <span className="subtle">{t.tip}</span>
            </div>
            {list.map((i) => (
              <InsightCard key={i.id} insight={i} defs={defs} targets={targets} />
            ))}
          </div>
        )
      })}
    </>
  )
}

function InsightCard({ insight: i, defs, targets }: { insight: Insight; defs: MetricDef[]; targets: InsightTarget[] }) {
  const scopes = useScopes()
  const def = defs.find((d) => d.key === i.metric)
  const editable = i.status === 'proposed' || i.status === 'rejected'
  const [confirm, setConfirm] = useState<null | 'write' | 'revert' | 'delete'>(null)
  const qc = useQueryClient()
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
  const [params] = useSearchParams()
  const focused = params.get('ins') === String(i.id)
  const [open, setOpen] = useState(focused)
  const cardRef = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (focused) cardRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [focused])
  const tier = TIERS.find((t) => t.key === tierOf(i))!
  // 선택 규칙이 측정해 보니 효과가 없으면 빼기를 권한다
  const dropHint = tierOf(i) === 'optional' && (i.status === 'adopted' || i.status === 'done') && (i.verdict === '변화 없음' || i.verdict === '악화')

  const overBudget = useOverBudget()
  const loadNote =
    `매 세션 약 ${tok(i.load.targetTokens)} → ${tok(i.load.targetTokens + i.load.always)}` +
    (i.load.targetTokens > 0 ? `(+${((100 * i.load.always) / i.load.targetTokens).toFixed(1)}%)` : '') +
    (overBudget(i.target) ? ' · 이 곳은 Vita 규칙 상한이에요' : '')
  // 접힌 줄 — 단계·제목·(결과)·넣을 곳·바로 할 일, 규칙 한 줄. 제목을 누르면 펼친다
  if (!open) {
    const v = i.verdict ?? '측정 중'
    const measured = i.status === 'adopted' || i.status === 'done'
    const canDrop = i.status === 'done' && (v === '변화 없음' || v === '악화')
    return (
      <article className={`ob-ins ${i.status} compact`} ref={cardRef}>
        <div className="ob-row">
          <span className={`ob-tier-chip ${tier.key}`} title={i.tierReason ?? tier.tip}>
            {tier.name}
          </span>
          <button className="ob-row-title" onClick={() => setOpen(true)} title="펼쳐서 근거·문안·지표 보기">
            {i.title}
          </button>
          {measured && (
            <span className={`ob-verdict ob-row-verdict ${VERDICT_TONE[v] ?? 'flat'}`} title={def?.label}>
              {v}
              {i.deltaPct != null && SHOW_DELTA.has(v) && ` ${i.deltaPct > 0 ? '+' : ''}${Math.round(i.deltaPct)}%`}
            </span>
          )}
          {i.status === 'adopted' && <span className="ob-row-meta subtle">일한 날 {i.afterDays}일</span>}
          {i.target && <LoadChip insight={i} over={overBudget(i.target)} />}
          {target && (
            <span className="ob-row-meta ob-row-target subtle" title={target.path}>
              {target.label}
            </span>
          )}
          <div className="ob-row-actions">
            {i.status === 'proposed' && (
              <>
                <button className="btn btn-primary btn-sm" disabled={!i.snippet || !i.target || !i.metric} onClick={() => setConfirm('write')} title={!i.metric ? '펼쳐서 지표를 먼저 고르세요' : ''}>
                  도입
                </button>
                <button className="btn btn-ghost btn-sm" onClick={() => patch.mutate({ status: 'rejected' })}>
                  기각
                </button>
                <select className="ob-tier-select" value={tierOf(i)} onChange={(e) => patch.mutate({ tier: e.target.value as InsightTier })} title="단계 바꾸기">
                  {TIERS.map((t) => (
                    <option key={t.key} value={t.key}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </>
            )}
            {i.status === 'rejected' && (
              <button className="btn btn-ghost btn-sm" onClick={() => patch.mutate({ status: 'proposed' })}>
                되살리기
              </button>
            )}
            {canDrop && (
              <button className="btn btn-sm" onClick={() => setConfirm('revert')} title="효과가 없었으니 md 에서 규칙을 빼요">
                규칙 빼기
              </button>
            )}
            <button className="btn btn-ghost icon-btn btn-sm" onClick={() => setOpen(true)} aria-label="펼치기">
              <Icon name="chevron" size={12} />
            </button>
          </div>
        </div>
        {i.snippet && <div className="ob-ins-rule one" title={i.snippet}>{i.snippet.replace(/^\s*-\s*/, '')}</div>}
        {i.status === 'proposed' && (i.effect || i.sessions.length > 0) && (
          <div className="ob-ins-why subtle">
            {i.effect && <span>예상: {i.effect}</span>}
            <SourceSessions insight={i} />
          </div>
        )}
        {err && <div className="ob-error">{err.message}</div>}
        {confirm && (
          <div className="ob-confirm">
            <span>
              {confirm === 'write' && `${target?.path ?? i.target} 에 넣고 측정을 시작할까요? ${loadNote}`}
              {confirm === 'revert' && `파일에서 문안을 빼고 제안 상태로 되돌릴까요? 매 세션 약 ${tok(i.load.always)} 줄어요. 기준값·측정은 지워져요.`}
            </span>
            <button
              className={`btn btn-sm${confirm === 'write' ? ' btn-primary' : ''}`}
              onClick={() => {
                if (confirm === 'write') adopt.mutate(true)
                if (confirm === 'revert') revert.mutate(undefined)
                setConfirm(null)
              }}
            >
              {confirm === 'write' ? '넣고 도입' : '빼기'}
            </button>
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirm(null)}>
              취소
            </button>
          </div>
        )}
      </article>
    )
  }

  return (
    <article className={`ob-ins ${i.status}${focused ? ' focus' : ''}`} ref={cardRef}>
      <div className="ob-ins-top">
        {editable ? (
          <InlineText className="ob-ins-title" value={i.title} onSave={(v) => v && patch.mutate({ title: v })} />
        ) : (
          <h4 className="ob-ins-title">{i.title}</h4>
        )}
        <span className="ob-ins-meta subtle">
          <span className={`ob-tier-chip ${tier.key}`} title={i.tierReason ?? tier.tip}>
            {tier.name}
          </span>
          {dropHint && <span className="ob-drop-hint">빼기 권유 — 선택 규칙인데 효과가 없어요</span>} #{i.id} · {i.source === 'ai' ? 'AI 제안' : '직접'} ·{' '}
          {fmtDateTime(i.createdAt)}
          <button className="btn btn-ghost btn-sm" onClick={() => setOpen(false)}>
            접기
          </button>
        </span>
      </div>

      {editable ? (
        <InlineText multiline className="ob-ins-problem" value={i.problem ?? ''} placeholder="근거 — 어떤 숫자를 보고 이렇게 바꾸나" onSave={(v) => patch.mutate({ problem: v })} />
      ) : (
        i.problem && <p className="ob-ins-problem">{i.problem}</p>
      )}
      {(i.effect || i.tierReason || i.sessions.length > 0) && (
        <div className="ob-ins-why subtle">
          {i.effect && <span>예상: {i.effect}</span>}
          {i.tierReason && <span>단계 이유: {i.tierReason}</span>}
          <SourceSessions insight={i} />
        </div>
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

      <div className="ob-signals">
        <span className="subtle" title="같은 실수가 다시 나면 도구 명령·오류 문구·정정 요청에 글자 그대로 나올 문자열 — 도입 효과에서 재발 횟수를 셀 때 써요. 쉼표로 나눠요">
          재발 신호
        </span>
        <InlineText
          className="ob-signals-input"
          value={i.signals.join(', ')}
          placeholder="예: sleep 600, push origin HEAD:main"
          onSave={(v) => {
            patch.mutate({ signals: v.split(',').map((x) => x.trim()).filter(Boolean) })
            qc.invalidateQueries({ queryKey: ['outcomes'] })
          }}
        />
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
            {' · '}
            {target.kind === 'memory'
              ? `색인 한 줄 약 ${tok(i.load.always)}은 매 세션, 본문 약 ${tok(i.load.onDemand)}은 관련 있을 때만 읽혀요`
              : `매 세션 약 ${tok(i.load.always)} 더 읽혀요`}
          </div>
        )}
      </div>

      {(i.status === 'adopted' || i.status === 'done') && <Effect insight={i} def={def} />}

      {err && <div className="ob-error">{err.message}</div>}

      {confirm ? (
        <div className="ob-confirm">
          <span>
            {confirm === 'write' && `${target?.path ?? i.target} 에 넣고 측정을 시작할까요? ${loadNote}`}
            {confirm === 'revert' && `파일에서 문안을 빼고 제안 상태로 되돌릴까요? 매 세션 약 ${tok(i.load.always)} 줄어요. 기준값·측정은 지워져요.`}
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
