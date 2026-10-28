import { useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { UsageOverview, UsageTotal } from '../api/types'
import { addDays, fmtTokens, fmtUsd, monthKey, parseYmd, today, weekMonday, weekday, ymd } from '../lib/format'
import { ErrorBox, Loading } from './common'
import { PARTS, totalTokens, UsageTable } from './UsageBreakdown'

// AI 사용 화면 맨 위 — 이 기간 크레딧(API 환산)과 실제 토큰 수를 모델별로. 아래 지표·병목보다 먼저 본다

/** 모델 순서(금액 큰 순)대로 쓰는 색 — 큰 면적이라 포인트색은 두 번째에만 */
const MODEL_COLORS = ['var(--ink)', 'var(--accent)', 'var(--c-teal)', 'var(--c-blue)', 'var(--c-purple)', 'var(--c-gray)']
/** 토큰 종류 색 — 캐시 읽기가 대부분이라 옅게 */
const PART_COLORS: Record<string, string> = {
  input: 'var(--c-blue)',
  output: 'var(--accent)',
  cacheRead: 'var(--border-strong)',
  cacheWrite5m: 'var(--c-teal)',
  cacheWrite1h: 'var(--c-purple)',
}

const short = (model: string) => model.replace(/^claude-/, '')
const exact = (n: number) => n.toLocaleString()
const perM = (cost: number, tokens: number) => (tokens > 0 ? `$${((cost / tokens) * 1e6).toFixed(2)}/M` : '—')
const pct = (v: number, total: number) => (total > 0 ? (v / total) * 100 : 0)
const fmtPct = (v: number) => (v >= 10 || v === 0 ? `${Math.round(v)}%` : `${v.toFixed(1)}%`)
/** 실제로 새로 처리한 토큰 — 캐시에서 다시 읽은 입력을 뺀 나머지 */
const fresh = (u: UsageTotal) => u.input + u.output + u.cacheWrite5m + u.cacheWrite1h

type Period = 'week' | 'month'

function range(week: string, period: Period): { from: string; to: string; label: string; prevLabel: string } {
  const mon = weekMonday(week)
  if (period === 'week') return { from: ymd(mon), to: ymd(addDays(mon, 6)), label: '이번 주', prevLabel: '지난주' }
  const thu = addDays(mon, 3)
  const first = new Date(thu.getFullYear(), thu.getMonth(), 1)
  const last = new Date(thu.getFullYear(), thu.getMonth() + 1, 0)
  return { from: ymd(first), to: ymd(last), label: `${thu.getMonth() + 1}월`, prevLabel: '지난달' }
}

export function UsageOverviewSection({ week, scope }: { week: string; scope: string | null }) {
  const [period, setPeriod] = useState<Period>('week')
  const r = range(week, period)
  const { data, isLoading, error } = useQuery({
    queryKey: ['usage-overview', r.from, r.to, scope],
    queryFn: () => api.usageOverview(r.from, r.to, scope),
  })
  return (
    <section className="card uo">
      <div className="uo-head">
        <div>
          <h3>토큰 사용량</h3>
          <p className="subtle">
            크레딧 = 토큰 × 모델 단가로 환산한 API 금액(구독 요금과 다름) · 서브에이전트 포함{scope ? ` · ${scope}` : ''}
          </p>
        </div>
        <div className="segmented">
          <button className={period === 'week' ? 'on' : ''} onClick={() => setPeriod('week')}>
            주
          </button>
          <button className={period === 'month' ? 'on' : ''} onClick={() => setPeriod('month')}>
            월 ({monthKey(addDays(weekMonday(week), 3)).slice(5).replace(/^0/, '')}월)
          </button>
        </div>
      </div>
      {error && <ErrorBox error={error} />}
      {isLoading && <Loading />}
      {data && (data.total.responses === 0 ? <p className="subtle uo-empty">이 기간 사용 기록이 없어요.</p> : <Body data={data} prevLabel={r.prevLabel} />)}
    </section>
  )
}

function Body({ data, prevLabel }: { data: UsageOverview; prevLabel: string }) {
  const t = data.total
  const tokens = totalTokens(t)
  const prevTokens = totalTokens(data.previous)
  const ended = data.to < today()
  const colorOf = new Map(data.models.map((m, i) => [m.model, MODEL_COLORS[Math.min(i, MODEL_COLORS.length - 1)]]))
  const unpricedTokens = data.models.filter((m) => !m.priced).reduce((s, m) => s + totalTokens(m.usage), 0)

  return (
    <>
      <div className="uo-kpis">
        <Kpi label="크레딧 (API 환산)" value={fmtUsd(t.costUsd)} sub={<Compare cur={t.costUsd} prev={data.previous.costUsd} fmt={fmtUsd} label={prevLabel} ended={ended} />} />
        <Kpi
          label="전체 토큰"
          value={fmtTokens(tokens)}
          title={`${exact(tokens)} 토큰`}
          sub={<Compare cur={tokens} prev={prevTokens} fmt={fmtTokens} label={prevLabel} ended={ended} />}
        />
        <Kpi
          label="새로 처리한 토큰"
          value={fmtTokens(fresh(t))}
          title={`${exact(fresh(t))} 토큰 — 입력 + 출력 + 캐시 쓰기`}
          sub={<span>캐시 읽기 {fmtPct(pct(t.cacheRead, tokens))} 뺀 나머지</span>}
        />
        <Kpi
          label="100만 토큰당 평균"
          value={perM(t.costUsd, tokens - unpricedTokens)}
          sub={
            <span>
              응답 {exact(t.responses)}회{unpricedTokens > 0 && ` · 단가 없는 ${fmtTokens(unpricedTokens)} 제외`}
            </span>
          }
        />
      </div>

      <div className="uo-block">
        <div className="uo-label">
          <b>토큰 구성</b>
          <span className="subtle">{exact(tokens)} 토큰</span>
        </div>
        <StackBar segments={PARTS.map((p) => ({ key: p.key, label: p.label, value: p.tokens(t), color: PART_COLORS[p.key], tip: p.tip }))} total={tokens} fmt={fmtTokens} />
      </div>

      <div className="uo-block">
        <div className="uo-label">
          <b>모델별 비중</b>
          <span className="subtle">같은 토큰이라도 모델 단가가 달라 크레딧 비중이 달라져요</span>
        </div>
        <div className="uo-share">
          <span className="uo-share-l">크레딧</span>
          <StackBar segments={data.models.map((m) => ({ key: m.model, label: short(m.model), value: m.usage.costUsd, color: colorOf.get(m.model)! }))} total={t.costUsd} fmt={fmtUsd} compact />
          <span className="uo-share-l">토큰</span>
          <StackBar segments={data.models.map((m) => ({ key: m.model, label: short(m.model), value: totalTokens(m.usage), color: colorOf.get(m.model)! }))} total={tokens} fmt={fmtTokens} compact />
        </div>
      </div>

      <div className="uo-models">
        {data.models.map((m) => {
          const mt = totalTokens(m.usage)
          return (
            <div key={m.model} className="uo-model">
              <div className="uo-model-head">
                <i style={{ background: colorOf.get(m.model) }} />
                <b title={m.model}>{short(m.model)}</b>
                <span className="tool-badge">{m.tool === 'codex' ? 'Codex' : 'Claude Code'}</span>
              </div>
              <div className="uo-model-nums">
                <div>
                  <span>크레딧</span>
                  <b className="num">{m.priced ? fmtUsd(m.usage.costUsd) : '단가 없음'}</b>
                  <small>{m.priced ? fmtPct(pct(m.usage.costUsd, t.costUsd)) : '—'}</small>
                </div>
                <div>
                  <span>토큰</span>
                  <b className="num" title={`${exact(mt)} 토큰`}>{fmtTokens(mt)}</b>
                  <small>{fmtPct(pct(mt, tokens))}</small>
                </div>
                <div>
                  <span>100만당</span>
                  <b className="num">{m.priced ? perM(m.usage.costUsd, mt) : '—'}</b>
                  <small>
                    응답 {exact(m.usage.responses)} · 세션 {m.sessions}
                  </small>
                </div>
              </div>
              <table className="uo-parts">
                <tbody>
                  {PARTS.filter((p) => p.tokens(m.usage) > 0).map((p) => {
                    const n = p.tokens(m.usage)
                    return (
                      <tr key={p.key} title={p.tip}>
                        <td>
                          <i style={{ background: PART_COLORS[p.key] }} />
                          {p.label}
                        </td>
                        <td className="num">{exact(n)}</td>
                        <td className="num subtle">{m.rate ? `× $${+m.rate[p.key].toFixed(3)}/M` : ''}</td>
                        <td className="num">{m.rate ? fmtUsd((n * m.rate[p.key]) / 1e6) : '—'}</td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          )
        })}
      </div>

      <Daily data={data} colorOf={colorOf} />

      <details className="uo-details">
        <summary>계산식 표 (토큰 종류 × 단가)</summary>
        <UsageTable models={data.models} />
      </details>
    </>
  )
}

function Kpi({ label, value, sub, title }: { label: string; value: string; sub: ReactNode; title?: string }) {
  return (
    <div className="uo-kpi">
      <span>{label}</span>
      <b className="num" title={title}>
        {value}
      </b>
      <small>{sub}</small>
    </div>
  )
}

/** 지난 기간 값. 기간이 끝났을 때만 증감률(진행 중이면 덜 쌓인 값이라 비교가 틀림) */
function Compare({ cur, prev, fmt, label, ended }: { cur: number; prev: number; fmt: (n: number) => string; label: string; ended: boolean }) {
  if (prev <= 0) return <span>{label} 기록 없음</span>
  const change = ((cur - prev) / prev) * 100
  return (
    <span>
      {label} {fmt(prev)}
      {ended && <em className={change > 10 ? 'up' : change < -10 ? 'down' : ''}> {change >= 0 ? '+' : ''}{Math.round(change)}%</em>}
    </span>
  )
}

function StackBar({
  segments,
  total,
  fmt,
  compact,
}: {
  segments: { key: string; label: string; value: number; color: string; tip?: string }[]
  total: number
  fmt: (n: number) => string
  compact?: boolean
}) {
  const shown = segments.filter((s) => s.value > 0)
  return (
    <div className={`uo-stack${compact ? ' compact' : ''}`}>
      <div className="uo-stack-bar">
        {shown.map((s) => (
          <span key={s.key} style={{ width: `${pct(s.value, total)}%`, background: s.color }} title={`${s.label} ${fmt(s.value)} · ${fmtPct(pct(s.value, total))}`} />
        ))}
      </div>
      <div className="uo-legend">
        {shown.map((s) => (
          <span key={s.key} title={s.tip}>
            <i style={{ background: s.color }} />
            {s.label} <b className="num">{fmt(s.value)}</b> <small>{fmtPct(pct(s.value, total))}</small>
          </span>
        ))}
      </div>
    </div>
  )
}

/** 날짜별 모델 쌓은 막대 — 크레딧/토큰 전환 */
function Daily({ data, colorOf }: { data: UsageOverview; colorOf: Map<string, string> }) {
  const [by, setBy] = useState<'cost' | 'tokens'>('cost')
  const val = (v: { costUsd: number; tokens: number }) => (by === 'cost' ? v.costUsd : v.tokens)
  const fmt = by === 'cost' ? fmtUsd : fmtTokens
  const sums = data.days.map((d) => d.models.reduce((s, m) => s + val(m), 0))
  const max = Math.max(1e-9, ...sums)
  const month = data.days.length > 7
  const todayKey = today()
  return (
    <div className="uo-block">
      <div className="uo-label">
        <b>날짜별</b>
        <div className="segmented sm">
          <button className={by === 'cost' ? 'on' : ''} onClick={() => setBy('cost')}>
            크레딧
          </button>
          <button className={by === 'tokens' ? 'on' : ''} onClick={() => setBy('tokens')}>
            토큰
          </button>
        </div>
      </div>
      <div className={`uo-days${month ? ' month' : ''}`}>
        {data.days.map((d, i) => (
          <div
            key={d.day}
            className={`uo-day${d.day === todayKey ? ' today' : ''}`}
            title={[`${d.day} · ${fmt(sums[i])}`, ...d.models.map((m) => `${short(m.model)} ${fmt(val(m))}`)].join('\n')}
          >
            <span className="uo-day-v num">{sums[i] > 0 && !month ? fmt(sums[i]) : ''}</span>
            <div className="uo-day-bar">
              {d.models.map((m) => (
                <span key={m.model} style={{ height: `${(val(m) / max) * 100}%`, background: colorOf.get(m.model) }} />
              ))}
            </div>
            <span className="uo-day-l">{month ? parseYmd(d.day).getDate() : weekday(d.day)}</span>
          </div>
        ))}
      </div>
    </div>
  )
}
