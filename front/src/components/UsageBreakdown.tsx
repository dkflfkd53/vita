import { Fragment, useEffect, useState, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { ModelRate, ModelUsage, UsageTotal } from '../api/types'
import { fmtTokens, fmtUsd } from '../lib/format'
import { Icon } from './Icon'
import { ErrorBox, Loading } from './common'

// 사용량(API 환산 $)이 어디서 나왔는지 — 모델별로 토큰 종류마다 양 × 단가 = 금액

const PARTS: { key: keyof ModelRate; label: string; tip: string; tokens: (u: UsageTotal) => number }[] = [
  { key: 'input', label: '입력', tip: '캐시를 거치지 않은 입력 토큰', tokens: (u) => u.input },
  { key: 'output', label: '출력', tip: 'Claude 가 쓴 토큰(생각·답변·도구 호출)', tokens: (u) => u.output },
  { key: 'cacheRead', label: '캐시 읽기', tip: '캐시에서 다시 읽은 입력 — 대화가 길수록 매 턴 커진다', tokens: (u) => u.cacheRead },
  { key: 'cacheWrite5m', label: '캐시 쓰기 5분', tip: '새 문맥을 5분짜리 캐시에 쓴 입력 (입력 단가 × 1.25)', tokens: (u) => u.cacheWrite5m },
  { key: 'cacheWrite1h', label: '캐시 쓰기 1시간', tip: '새 문맥을 1시간짜리 캐시에 쓴 입력 (입력 단가 × 2)', tokens: (u) => u.cacheWrite1h },
]

export function totalTokens(u: UsageTotal): number {
  return u.input + u.output + u.cacheRead + u.cacheWrite5m + u.cacheWrite1h
}

const exact = (n: number) => `${n.toLocaleString()} 토큰`
const fmtRate = (v: number) => `$${v < 1 ? v.toFixed(2) : v % 1 === 0 ? v.toFixed(0) : v.toFixed(2)}/M`

/** 좁은 자리(리포트 옆 칸)용 — 모델·토큰 합계·금액만, 누르면 종류별 계산식 */
export function UsageCompact({ models }: { models: ModelUsage[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (models.length === 0) return <p className="subtle" style={{ fontSize: 12.5, margin: 0 }}>이 기간 사용량이 없어요.</p>
  return (
    <div className="uc">
      {models.map((m) => {
        const isOpen = open === m.model
        return (
          <div key={m.model} className={`uc-item${isOpen ? ' open' : ''}`}>
            <button type="button" className="uc-row" onClick={() => setOpen(isOpen ? null : m.model)}>
              <Icon name="chevron" size={11} className={isOpen ? 'rot' : ''} />
              <span className="uc-model">{m.model.replace(/^claude-/, '')}</span>
              <span className="num uc-tok" title={exact(totalTokens(m.usage))}>{fmtTokens(totalTokens(m.usage))}</span>
              <b className="num">{m.priced ? fmtUsd(m.usage.costUsd) : '단가 없음'}</b>
            </button>
            {isOpen && (
              <div className="uc-parts">
                {PARTS.filter((p) => p.tokens(m.usage) > 0).map((p) => (
                  <div key={p.key} title={p.tip}>
                    <span>{p.label}</span>
                    <span className="num">
                      {p.tokens(m.usage).toLocaleString()}
                      {m.rate && ` × ${fmtRate(m.rate[p.key])}`}
                    </span>
                    <b className="num">{m.rate ? fmtUsd((p.tokens(m.usage) * m.rate[p.key]) / 1e6) : '—'}</b>
                  </div>
                ))}
                <div className="uc-resp">응답 {m.usage.responses.toLocaleString()}회</div>
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

/** 모델별 토큰·단가·금액 표. 줄을 누르면 계산식 */
export function UsageTable({ models }: { models: ModelUsage[] }) {
  const [open, setOpen] = useState<string | null>(null)
  if (models.length === 0) return <p className="subtle" style={{ fontSize: 12.5, margin: 0 }}>이 기간 사용량이 없어요.</p>
  const sum = models.reduce(
    (t, m) => ({
      responses: t.responses + m.usage.responses,
      input: t.input + m.usage.input,
      output: t.output + m.usage.output,
      cacheRead: t.cacheRead + m.usage.cacheRead,
      cacheWrite5m: t.cacheWrite5m + m.usage.cacheWrite5m,
      cacheWrite1h: t.cacheWrite1h + m.usage.cacheWrite1h,
      costUsd: t.costUsd + m.usage.costUsd,
      unpriced: t.unpriced + m.usage.unpriced,
    }),
    { responses: 0, input: 0, output: 0, cacheRead: 0, cacheWrite5m: 0, cacheWrite1h: 0, costUsd: 0, unpriced: 0 } as UsageTotal,
  )
  return (
    <div className="ut-wrap">
      <table className="ut">
        <thead>
          <tr>
            <th>모델</th>
            {PARTS.map((p) => (
              <th key={p.key} className="r" title={p.tip}>
                {p.label}
              </th>
            ))}
            <th className="r">토큰 합계</th>
            <th className="r">환산</th>
          </tr>
        </thead>
        <tbody>
          {models.map((m) => {
            const isOpen = open === m.model
            return (
              <Fragment key={m.model}>
                <tr className={`ut-row${isOpen ? ' open' : ''}`} onClick={() => setOpen(isOpen ? null : m.model)} title="눌러서 계산식 보기">
                  <td className="ut-model">
                    <Icon name="chevron" size={11} className={isOpen ? 'rot' : ''} /> {m.model.replace(/^claude-/, '')}
                    <span className="ut-resp">응답 {m.usage.responses.toLocaleString()}</span>
                  </td>
                  {PARTS.map((p) => {
                    const n = p.tokens(m.usage)
                    return (
                      <td key={p.key} className="r num" title={exact(n)}>
                        {n > 0 ? fmtTokens(n) : '—'}
                        {m.rate && n > 0 && <small>{fmtUsd((n * m.rate[p.key]) / 1e6)}</small>}
                      </td>
                    )
                  })}
                  <td className="r num" title={exact(totalTokens(m.usage))}>
                    <b>{fmtTokens(totalTokens(m.usage))}</b>
                  </td>
                  <td className="r num">
                    {m.priced ? <b>{fmtUsd(m.usage.costUsd)}</b> : <span title="단가표(application.yml worklog.pricing)에 없는 모델 — 0 으로 셈">단가 없음</span>}
                  </td>
                </tr>
                {isOpen && (
                  <tr className="ut-formula">
                    <td colSpan={PARTS.length + 3}>
                      {m.rate ? (
                        <>
                          {PARTS.filter((p) => p.tokens(m.usage) > 0).map((p, i) => (
                            <span key={p.key}>
                              {i > 0 && <em> + </em>}
                              {p.label} {p.tokens(m.usage).toLocaleString()} × {fmtRate(m.rate![p.key])} = <b>{fmtUsd((p.tokens(m.usage) * m.rate![p.key]) / 1e6)}</b>
                            </span>
                          ))}
                          <em> = </em>
                          <b>{fmtUsd(m.usage.costUsd)}</b>
                        </>
                      ) : (
                        '단가표에 없는 모델이라 금액을 셈하지 않았어요.'
                      )}
                    </td>
                  </tr>
                )}
              </Fragment>
            )
          })}
        </tbody>
        {models.length > 1 && (
          <tfoot>
            <tr>
              <td>합계</td>
              {PARTS.map((p) => (
                <td key={p.key} className="r num" title={exact(p.tokens(sum))}>
                  {p.tokens(sum) > 0 ? fmtTokens(p.tokens(sum)) : '—'}
                </td>
              ))}
              <td className="r num" title={exact(totalTokens(sum))}>
                <b>{fmtTokens(totalTokens(sum))}</b>
              </td>
              <td className="r num">
                <b>{fmtUsd(sum.costUsd)}</b>
              </td>
            </tr>
          </tfoot>
        )}
      </table>
      <p className="ut-note">
        Claude API 단가($/100만 토큰)로 환산한 추정치예요. 구독 요금과는 달라요. 서브에이전트 사용량도 들어 있어요. 캐시 쓰기 단가 = 입력 단가 × 1.25(5분) / × 2(1시간). 줄을 누르면 계산식이 보여요.
      </p>
    </div>
  )
}

/** 기간 사용량 창 — 금액 숫자를 누르면 뜬다 */
export function UsageDialog({ from, to, title, onClose }: { from: string; to: string; title: string; onClose: () => void }) {
  const { data, isLoading, error } = useQuery({ queryKey: ['usage', from, to], queryFn: () => api.usage(from, to) })
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <div className="dlg-backdrop" onClick={onClose}>
      <div className="dlg usage-dlg" onClick={(e) => e.stopPropagation()} role="dialog" aria-label={`${title} 사용량`}>
        <div className="dlg-head">
          <div>
            <h2 style={{ margin: 0, fontSize: 16 }}>{title} 사용량</h2>
            {data && (
              <p className="subtle" style={{ margin: '4px 0 0', fontSize: 12.5 }}>
                토큰 <b className="num" title={exact(totalTokens(data.total))}>{fmtTokens(totalTokens(data.total))}</b> · 응답{' '}
                {data.total.responses.toLocaleString()}회 · API 환산 <b className="num">{fmtUsd(data.total.costUsd)}</b>
              </p>
            )}
          </div>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={onClose} aria-label="닫기">
            <Icon name="x" />
          </button>
        </div>
        <div style={{ padding: '10px 18px 18px', overflow: 'auto' }}>
          {error && <ErrorBox error={error} />}
          {isLoading && <Loading />}
          {data && <UsageTable models={data.models} />}
        </div>
      </div>
    </div>
  )
}

/** 금액을 감싸 누르면 토큰 내역을 띄우는 버튼 */
export function UsageButton({ from, to, title, children, className }: { from: string; to: string; title: string; children: ReactNode; className?: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button type="button" className={`usage-btn ${className ?? 'usage-inline'}`} onClick={() => setOpen(true)} title="눌러서 모델별 토큰·계산식 보기">
        {children}
      </button>
      {open && <UsageDialog from={from} to={to} title={title} onClose={() => setOpen(false)} />}
    </>
  )
}
