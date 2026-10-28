import { useMemo } from 'react'
import { useNavigate } from 'react-router-dom'
import type { CalendarDay } from '../api/types'
import { hiddenPerDay, layoutWeek, type Segment } from '../lib/calendarLayout'
import { addDays, fmtDuration, fmtShort, fmtUsd, itemColor, parseYmd, ymd } from '../lib/format'
import { Icon } from './Icon'

// 캘린더 칸·막대(세션 화면)
// 주말은 일하지 않아 칸에서 뺀다(기록은 지우지 않으므로 합계에는 남는다)

export const WEEKDAYS = ['월', '화', '수', '목', '금']
const MONTH_LANES = 3

/** 그 날이 든 주의 월요일(ISO 주와 맞춘다) */
export function mondayOf(d: Date): Date {
  return addDays(d, -((d.getDay() + 6) % 7))
}

export function weekDays(monday: Date): string[] {
  return Array.from({ length: 5 }, (_, i) => ymd(addDays(monday, i)))
}

/** 앞뒤 근무일 — 월요일의 앞은 전주 금, 금요일의 뒤는 다음 주 월 */
export const prevWorkday = (day: string) => ymd(addDays(parseYmd(day), -3))
export const nextWorkday = (day: string) => ymd(addDays(parseYmd(day), 3))

/** 달력에 그릴 주들(월요일 시작, 평일만) */
export function monthWeeks(anchor: Date): string[][] {
  const first = new Date(anchor.getFullYear(), anchor.getMonth(), 1)
  const last = new Date(anchor.getFullYear(), anchor.getMonth() + 1, 0)
  const rows: string[][] = []
  for (let m = mondayOf(first); m <= last; m = addDays(m, 7)) rows.push(weekDays(m))
  return rows
}

// ───────── 공통 막대 ─────────

function SegmentBar({ seg, row, compact }: { seg: Segment; row: number; compact?: boolean }) {
  const navigate = useNavigate()
  const color = itemColor(seg.project, seg.color)
  const span = seg.end - seg.start + 1
  const tip = [
    seg.title,
    seg.perDay
      .map((d) => `${Number(d.day.slice(5, 7))}/${Number(d.day.slice(8))} ${fmtShort(d.activeSec)} ${fmtUsd(d.costUsd)}`)
      .join(' · '),
    `합계 ${fmtDuration(seg.activeSec)} · ${fmtUsd(seg.costUsd)}`,
  ].join('\n')
  return (
    <button
      className={`seg${compact ? ' compact' : ''}${seg.contLeft ? ' cont-l' : ''}${seg.contRight ? ' cont-r' : ''}`}
      style={{
        gridColumn: `${seg.start + 1} / ${seg.end + 2}`,
        gridRow: row,
        ['--seg' as string]: color,
      }}
      title={tip}
      onClick={(e) => {
        e.stopPropagation()
        navigate(`/sessions/${seg.taskKey}`)
      }}
    >
      <span className="seg-title">{seg.title}</span>
      {(!compact || span > 1) && <span className="seg-h">{fmtShort(seg.activeSec)}</span>}
    </button>
  )
}

function dayClass(day: string, todayKey: string, selected: string | null, extra = '') {
  const dow = parseYmd(day).getDay()
  return ['cal-cell', extra, day === todayKey && 'today', day === selected && 'selected', dow === 0 && 'sun', dow === 6 && 'sat']
    .filter(Boolean)
    .join(' ')
}

// ───────── 주 보기 ─────────

/** 한 주 — 막대 + 날짜별 요약 줄 */
export function WeekView({
  days,
  byDay,
  todayKey,
  selected,
  onSelect,
}: {
  days: string[]
  byDay: Map<string, CalendarDay>
  todayKey: string
  selected: string | null
  onSelect: (day: string) => void
}) {
  const segments = useMemo(() => layoutWeek(days, byDay, prevWorkday(days[0]), nextWorkday(days[days.length - 1])), [days, byDay])
  const lanes = Math.max(3, ...segments.map((s) => s.lane + 1))
  return (
    <div className="cal wk" style={{ gridTemplateRows: `56px repeat(${lanes}, 30px) minmax(120px, auto)` }}>
      {days.map((day, i) => (
        <div
          key={`bg-${day}`}
          className={dayClass(day, todayKey, selected, 'wk-bg')}
          style={{ gridColumn: i + 1, gridRow: '1 / -1' }}
          onClick={() => onSelect(day)}
        />
      ))}
      {days.map((day, i) => {
        const d = byDay.get(day)
        const date = parseYmd(day)
        return (
          <div key={`h-${day}`} className={dayClass(day, todayKey, selected, 'wk-head')} style={{ gridColumn: i + 1, gridRow: 1 }}>
            <span className="wk-dow">{WEEKDAYS[i]}</span>
            <span className="daynum">{date.getDate()}</span>
            {d && d.activeSec > 0 && (
              <span className="day-total">
                {fmtShort(d.activeSec)} · {fmtUsd(d.costUsd)}
              </span>
            )}
          </div>
        )
      })}
      {segments.map((s) => (
        <SegmentBar key={`${s.taskKey}-${s.start}`} seg={s} row={s.lane + 2} />
      ))}
      {days.map((day, i) => {
        const d = byDay.get(day)
        return (
          <div key={`s-${day}`} className="wk-summary" style={{ gridColumn: i + 1, gridRow: lanes + 2 }}>
            {d?.overview && <p>{d.overview}</p>}
            {d && (d.commits > 0 || d.hasSummary) && (
              <div className="cal-foot">
                {d.hasSummary && (
                  <span className="ai" title="AI 요약 있음">
                    <Icon name="sparkle" size={12} />
                  </span>
                )}
                {d.commits > 0 && (
                  <span style={{ display: 'inline-flex', gap: 3, alignItems: 'center' }}>
                    <Icon name="commit" size={12} /> {d.commits}
                  </span>
                )}
              </div>
            )}
          </div>
        )
      })}
    </div>
  )
}

// ───────── 월 보기 (주 한 줄) ─────────

function MonthWeek({
  days,
  byDay,
  month,
  todayKey,
  selected,
  onSelect,
}: {
  days: string[]
  byDay: Map<string, CalendarDay>
  month: number
  todayKey: string
  selected: string | null
  onSelect: (day: string) => void
}) {
  const segments = useMemo(() => layoutWeek(days, byDay, prevWorkday(days[0]), nextWorkday(days[days.length - 1])), [days, byDay])
  const hidden = hiddenPerDay(segments, MONTH_LANES, days.length)
  return (
    <div className="mrow" style={{ gridTemplateRows: `30px repeat(${MONTH_LANES}, 23px) minmax(26px, 1fr)` }}>
      {days.map((day, i) => (
        <div
          key={`bg-${day}`}
          className={dayClass(day, todayKey, selected, `m-bg${parseYmd(day).getMonth() !== month ? ' other' : ''}`)}
          style={{ gridColumn: i + 1, gridRow: '1 / -1' }}
          onClick={() => onSelect(day)}
          title={
            [byDay.get(day)?.costUsd ? `사용량 ${fmtUsd(byDay.get(day)?.costUsd)}` : '', byDay.get(day)?.overview ?? '']
              .filter(Boolean)
              .join('\n') || undefined
          }
        />
      ))}
      {days.map((day, i) => {
        const d = byDay.get(day)
        return (
          <div
            key={`h-${day}`}
            className={dayClass(day, todayKey, selected, `m-head${parseYmd(day).getMonth() !== month ? ' other' : ''}`)}
            style={{ gridColumn: i + 1, gridRow: 1 }}
          >
            <span className="daynum">{parseYmd(day).getDate()}</span>
            {d && d.activeSec > 0 && <span className="day-total">{fmtShort(d.activeSec)}</span>}
          </div>
        )
      })}
      {segments
        .filter((s) => s.lane < MONTH_LANES)
        .map((s) => (
          <SegmentBar key={`${s.taskKey}-${s.start}`} seg={s} row={s.lane + 2} compact />
        ))}
      {days.map((day, i) => {
        const d = byDay.get(day)
        if (!hidden[i] && !d?.commits && !d?.hasSummary) return null
        return (
          <div key={`f-${day}`} className="m-foot" style={{ gridColumn: i + 1, gridRow: MONTH_LANES + 2 }}>
            {hidden[i] > 0 && <span>+{hidden[i]}개 더</span>}
            {d?.hasSummary && (
              <span className="ai" title="AI 요약 있음">
                <Icon name="sparkle" size={12} />
              </span>
            )}
            {!!d?.commits && (
              <span style={{ display: 'inline-flex', gap: 3, alignItems: 'center' }}>
                <Icon name="commit" size={12} /> {d.commits}
              </span>
            )}
          </div>
        )
      })}
    </div>
  )
}

/** 한 달 격자 — 월요일 시작 평일 5칸 */
export function MonthGrid({
  anchor,
  byDay,
  todayKey,
  selected,
  onSelect,
}: {
  anchor: Date
  byDay: Map<string, CalendarDay>
  todayKey: string
  selected: string | null
  onSelect: (day: string) => void
}) {
  return (
    <div className="cal">
      <div className="cal-head">
        {WEEKDAYS.map((w) => (
          <div key={w}>{w}</div>
        ))}
      </div>
      {monthWeeks(anchor).map((days) => (
        <MonthWeek
          key={days[0]}
          days={days}
          byDay={byDay}
          month={anchor.getMonth()}
          todayKey={todayKey}
          selected={selected}
          onSelect={onSelect}
        />
      ))}
    </div>
  )
}
