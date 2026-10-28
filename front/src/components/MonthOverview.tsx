import { Link } from 'react-router-dom'
import type { Board, WorkItem, WorkStatus } from '../api/types'
import { addDays, fmtShort, fmtUsd, monthWeek, parseYmd, today, weekKey, weekLabel, weekMonday } from '../lib/format'
import { heatLevel } from './Heatmap'
import { ProjectLabel } from './common'
import { UsageButton } from './UsageBreakdown'
import { STATUS, StatusIcon } from './workFields'

// 월 보기는 목록이 아니라 요약: 숫자 카드 + 할 일 × 주 흐름표 + 계획 외 상위 5개. 편집은 주 보기에서 한다
// 참고: Linear Cycles 진행 요약, Sunsama 주간 회고

const NO_CATEGORY = '(카테고리 없음)'
const HEAT = ['transparent', 'var(--heat-1)', 'var(--heat-2)', 'var(--heat-3)', 'var(--heat-4)']
const STATUS_ORDER: WorkStatus[] = ['DONE', 'IN_PROGRESS', 'PLANNED', 'ON_HOLD']
const LOOSE_TOP = 5

function isWeekday(day: string) {
  const dow = parseYmd(day).getDay()
  return dow !== 0 && dow !== 6
}

/** 날짜별 작업 → 주별 초 */
function byWeek(days: { day: string; activeSec: number }[]): Map<string, number> {
  const m = new Map<string, number>()
  for (const d of days) {
    const wk = weekKey(parseYmd(d.day))
    m.set(wk, (m.get(wk) ?? 0) + d.activeSec)
  }
  return m
}

export function MonthOverview({ board, onWeek }: { board: Board; onWeek: (week: string) => void }) {
  const s = board.stats
  const weeks = board.weeks
  const thisWeek = weekKey(parseYmd(today()))
  const boardMonth = parseYmd(board.from).getMonth() + 1

  const counts = Object.fromEntries(STATUS_ORDER.map((st) => [st, board.items.filter((i) => i.status === st).length])) as Record<
    WorkStatus,
    number
  >
  const allDays = [...board.items.flatMap((i) => i.sessionDays), ...board.unassigned.flatMap((u) => u.days)]
  const workedDays = new Set(allDays.filter((d) => d.activeSec > 0 && isWeekday(d.day)).map((d) => d.day)).size
  const weekTotals = byWeek(allDays)
  const maxWeek = Math.max(1, ...weeks.map((w) => weekTotals.get(w) ?? 0))
  const linkRate = s.activeSec > 0 ? Math.round((s.linkedSec / s.activeSec) * 100) : 0

  const groups = new Map<string, WorkItem[]>()
  for (const c of board.categories) groups.set(c, [])
  for (const item of board.items) {
    const key = item.category ?? NO_CATEGORY
    if (!groups.has(key)) groups.set(key, [])
    groups.get(key)!.push(item)
  }
  const cols = `minmax(220px, 1fr) repeat(${weeks.length}, minmax(64px, 84px)) 64px`

  const loose = [...board.unassigned].sort((a, b) => b.activeSec - a.activeSec)
  const looseSec = loose.reduce((t, u) => t + u.activeSec, 0)
  const maxLoose = Math.max(1, ...loose.slice(0, LOOSE_TOP).map((u) => u.activeSec))

  return (
    <div className="mo">
      <div className="mo-cards">
        <div className="mo-card">
          <b>{board.items.length}</b>
          <span className="mo-label">할 일</span>
          <span className="mo-status">
            {STATUS_ORDER.filter((st) => counts[st] > 0).map((st) => (
              <span key={st} title={STATUS[st].label}>
                <StatusIcon status={st} size={12} /> {counts[st]}
              </span>
            ))}
            {board.items.length === 0 && <span>계획 없음</span>}
          </span>
        </div>
        <div className="mo-card">
          <b>{fmtShort(s.activeSec)}</b>
          <span className="mo-label">작업 시간</span>
          <span className="mo-sub">평일 {workedDays}일 일함</span>
        </div>
        <div className="mo-card">
          <b>{fmtShort(s.linkedSec)}</b>
          <span className="mo-label">할 일에 연결</span>
          <span className="mo-sub">연결률 {linkRate}%</span>
        </div>
        <UsageButton from={board.from} to={board.to} title={board.label} className="mo-card">
          <b>{fmtUsd(s.costUsd)}</b>
          <span className="mo-label">사용량(API 환산)</span>
          <span className="mo-sub">할 일 {fmtUsd(s.linkedCostUsd)} · 눌러서 토큰 보기</span>
        </UsageButton>
      </div>

      <section className="mo-flow">
        <h3 className="mo-title">주별 흐름</h3>
        <div className="mo-grid" style={{ gridTemplateColumns: cols }}>
          <div className="mo-hcell mo-hname">할 일</div>
          {weeks.map((w) => {
            const mon = weekMonday(w)
            const fri = addDays(mon, 4)
            const sec = weekTotals.get(w) ?? 0
            return (
              <button
                key={w}
                className={`mo-hcell mo-week${w === thisWeek ? ' now' : ''}`}
                onClick={() => onWeek(w)}
                title={`${weekLabel(w)} ${mon.getMonth() + 1}/${mon.getDate()}~${fri.getMonth() + 1}/${fri.getDate()} · ${fmtShort(sec)} — 눌러서 그 주 보기`}
              >
                <span className="mo-wk">{monthWeek(w).month === boardMonth ? `${monthWeek(w).n}주차` : weekLabel(w)}</span>
                <span className="mo-wd">
                  {mon.getMonth() + 1}/{mon.getDate()}~
                </span>
                <span className="mo-wbar">
                  <span style={{ height: `${Math.max(sec > 0 ? 8 : 0, (sec / maxWeek) * 100)}%` }} />
                </span>
                <span className="mo-wh num">{sec > 0 ? fmtShort(sec) : '—'}</span>
              </button>
            )
          })}
          <div className="mo-hcell mo-htotal">합계</div>

          {[...groups.entries()]
            .filter(([, items]) => items.length > 0)
            .map(([category, items]) => (
              <Group key={category} category={category} items={items} weeks={weeks} thisWeek={thisWeek} />
            ))}
        </div>
        {board.items.length === 0 && <p className="mo-empty">이 달에 계획된 할 일이 없어요. 주 보기에서 할 일을 추가하세요.</p>}
        <div className="mo-legend">
          <span>
            <i className="mo-cell planned" /> 계획만
          </span>
          <span>
            <i className="mo-cell worked" style={{ background: HEAT[3] }} /> 작업함 (진할수록 많이)
          </span>
          <span>
            <i className="mo-dot">·</i> 계획 없음
          </span>
          <span className="mo-legend-tip">주를 누르면 그 주 화면으로, 할 일을 누르면 상세로 가요</span>
        </div>
      </section>

      {loose.length > 0 && (
        <section className="mo-loose">
          <h3 className="mo-title">
            계획 외 작업 <span className="count">{loose.length}건 · {fmtShort(looseSec)}</span>
          </h3>
          {loose.slice(0, LOOSE_TOP).map((u) => (
            <div key={u.sessionId} className="mo-lrow">
              <Link to={`/sessions/${u.taskKey}`} className="mo-lname" title={u.title}>
                {u.title}
              </Link>
              <ProjectLabel project={u.project} />
              <span className="mo-lbar">
                <span style={{ width: `${(u.activeSec / maxLoose) * 100}%` }} />
              </span>
              <span className="mo-lv num">
                {fmtShort(u.activeSec)} · {fmtUsd(u.costUsd)}
              </span>
            </div>
          ))}
          <button className="btn btn-ghost btn-sm mo-lmore" onClick={() => onWeek(board.weekKey)}>
            {loose.length > LOOSE_TOP ? `외 ${loose.length - LOOSE_TOP}건 · ` : ''}주 보기에서 정리하기 →
          </button>
        </section>
      )}
    </div>
  )
}

function Group({ category, items, weeks, thisWeek }: { category: string; items: WorkItem[]; weeks: string[]; thisWeek: string }) {
  return (
    <>
      <div className="mo-cat" style={{ gridColumn: '1 / -1' }}>
        {category}
        <span className="count">
          {items.filter((i) => i.status === 'DONE').length}/{items.length}
        </span>
      </div>
      {items.map((item) => {
        const sec = byWeek(item.sessionDays)
        return (
          <div key={item.id} className={`mo-row${item.status === 'DONE' ? ' done' : ''}`}>
            <Link to={`/work/${item.id}`} className="mo-name" title={item.title}>
              <StatusIcon status={item.status} size={14} />
              <span>{item.title}</span>
            </Link>
            {weeks.map((w) => {
              const s = sec.get(w) ?? 0
              const planned = item.weeks.includes(w)
              return (
                <div key={w} className={`mo-c${w === thisWeek ? ' now' : ''}`}>
                  {s > 0 ? (
                    <span className="mo-cell worked" style={{ background: HEAT[heatLevel(s)] }} title={`${fmtShort(s)}${planned ? '' : ' · 계획엔 없었음'}`}>
                      {fmtShort(s)}
                    </span>
                  ) : planned ? (
                    <span className="mo-cell planned" title="계획했지만 연결된 작업 없음" />
                  ) : (
                    <span className="mo-dot">·</span>
                  )}
                </div>
              )
            })}
            <div className="mo-total num">{item.activeSec > 0 ? fmtShort(item.activeSec) : '—'}</div>
          </div>
        )
      })}
    </>
  )
}
