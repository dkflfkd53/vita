import { useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useHeatmap } from '../api/hooks'
import { api } from '../api/client'
import { DayPanel } from '../components/DayPanel'
import { Heatmap } from '../components/Heatmap'
import { TasksPage } from './TasksPage'
import { Icon } from '../components/Icon'
import { ErrorBox } from '../components/common'
import { UsageButton } from '../components/UsageBreakdown'
import { MonthGrid, WeekView, mondayOf, monthWeeks, nextWorkday, prevWorkday, weekDays } from '../components/CalendarGrid'
import { addDays, fmtShort, fmtUsd, parseYmd, today, weekKey, weekLabel, weekMonday, ymd } from '../lib/format'

const HEAT_WEEKS = 52
const VIEW_KEY = 'worklog-cal-view'

type View = 'week' | 'month' | 'list'

function readView(): View {
  try {
    const v = localStorage.getItem(VIEW_KEY)
    return v === 'month' || v === 'list' ? v : 'week'
  } catch {
    return 'week'
  }
}

export function CalendarPage() {
  const [params, setParams] = useSearchParams()
  const [savedView, setSavedView] = useState<View>(readView)
  const todayKey = today()
  const view: View = (params.get('view') as View) ?? savedView
  const selected = params.get('day')
  // 기준 날짜: d > 선택한 날 > month(예전 링크 호환) > 오늘
  const anchor = params.get('d') ?? selected ?? (params.get('month') ? `${params.get('month')}-01` : todayKey)
  const anchorDate = parseYmd(anchor)

  const update = (next: { d?: string; day?: string | null; view?: View }) => {
    const p = new URLSearchParams(params)
    p.delete('month')
    if (next.d) p.set('d', next.d)
    if (next.day === null) p.delete('day')
    else if (next.day) p.set('day', next.day)
    if (next.view) {
      p.delete('view')
      setSavedView(next.view)
      try {
        localStorage.setItem(VIEW_KEY, next.view)
      } catch {
        // 기억 못 해도 이번엔 바뀐다
      }
    }
    setParams(p, { replace: true })
  }
  const shift = (n: number) => {
    if (view === 'week') update({ d: ymd(addDays(anchorDate, n * 7)) })
    else update({ d: ymd(new Date(anchorDate.getFullYear(), anchorDate.getMonth() + n, 1)) })
  }
  const selectDay = (day: string) => update({ d: day, day: day === selected ? null : day })

  // 보이는 칸(주 7일 / 월 격자) + 앞뒤 하루 — 주 경계를 넘어 이어지는지 판정용
  const weeks = useMemo(() => {
    if (view === 'week') return [weekDays(mondayOf(anchorDate))]
    return monthWeeks(anchorDate)
  }, [view, anchor])
  const from = prevWorkday(weeks[0][0])
  const to = nextWorkday(weeks[weeks.length - 1][4])
  const cal = useQuery({ queryKey: ['calendar', from, to], queryFn: () => api.calendarRange(from, to), refetchInterval: 60_000 })
  const byDay = useMemo(() => new Map((cal.data ?? []).map((d) => [d.day, d])), [cal.data])

  // 잔디: 이번 주 금요일까지 52주, 월요일 시작 평일만
  const heatTo = addDays(mondayOf(parseYmd(todayKey)), 4)
  const heatFrom = addDays(mondayOf(parseYmd(todayKey)), -(HEAT_WEEKS - 1) * 7)
  const heat = useHeatmap(ymd(heatFrom), ymd(heatTo))
  // 숫자 카드는 보고 있는 주·달 기준(주 보기 = 그 주, 월 보기 = 고른 날이 든 주 / 그 달)
  const shownWeek = weekKey(mondayOf(selected ? parseYmd(selected) : anchorDate))
  const shownMonth = (selected ?? anchor).slice(0, 7)
  const weekName = shownWeek === weekKey(new Date()) ? '이번 주' : weekLabel(shownWeek)
  const monthName = shownMonth === todayKey.slice(0, 7) ? '이번 달' : `${Number(shownMonth.slice(5))}월`
  const kpi = useMemo(() => {
    const days = heat.data ?? []
    const week = days.filter((d) => weekKey(parseYmd(d.day)) === shownWeek)
    const mon = days.filter((d) => d.day.startsWith(shownMonth))
    return {
      week: week.reduce((s, d) => s + d.activeSec, 0),
      month: mon.reduce((s, d) => s + d.activeSec, 0),
      weekCost: week.reduce((s, d) => s + d.costUsd, 0),
      monthCost: mon.reduce((s, d) => s + d.costUsd, 0),
      monthDays: mon.filter((d) => d.activeSec > 0).length,
      monthCommits: mon.reduce((s, d) => s + d.commits, 0),
    }
  }, [heat.data, shownWeek, shownMonth])

  const title =
    view === 'week'
      ? (() => {
          const [mon, sun] = [parseYmd(weeks[0][0]), parseYmd(weeks[0][4])]
          return `${weekLabel(weekKey(mon), true)} (${mon.getMonth() + 1}/${mon.getDate()}~${sun.getMonth() + 1}/${sun.getDate()})`
        })()
      : view === 'month'
        ? `${anchorDate.getFullYear()}년 ${anchorDate.getMonth() + 1}월`
        : '전체 세션'
  const isList = view === 'list'

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>세션</h1>
          <p>Claude Code 세션에서 진행한 작업을 날짜별·세션별로 모아 봐요. 며칠 이어진 세션은 막대로 이어져요.</p>
        </div>
      </div>

      <div className="overview-row">
        <div className="card">
          <div className="card-head">
            <h3>최근 1년 작업량</h3>
          </div>
          <div className="card-body">
            {heat.error ? <ErrorBox error={heat.error} /> : <Heatmap days={heat.data ?? []} today={todayKey} selected={selected} onSelect={selectDay} />}
          </div>
        </div>
        <div className="kpis">
          <div className="kpi">
            <b className="num">{fmtShort(kpi.week)}</b>
            <span>{weekName} 작업 시간</span>
          </div>
          <div className="kpi">
            <b className="num">{fmtShort(kpi.month)}</b>
            <span>{monthName} 작업 시간</span>
          </div>
          <div className="kpi">
            <b className="num">{kpi.monthDays}일</b>
            <span>{monthName} 일한 날</span>
          </div>
          <div className="kpi">
            <b className="num">{kpi.monthCommits}</b>
            <span>{monthName} 커밋</span>
          </div>
          <UsageButton from={ymd(weekMonday(shownWeek))} to={ymd(addDays(weekMonday(shownWeek), 6))} title={weekLabel(shownWeek, true)} className="kpi">
            <b className="num">{fmtUsd(kpi.weekCost)}</b>
            <span>{weekName} 사용량(API 환산)</span>
          </UsageButton>
          <UsageButton
            from={`${shownMonth}-01`}
            to={ymd(new Date(Number(shownMonth.slice(0, 4)), Number(shownMonth.slice(5)), 0))}
            title={`${shownMonth.slice(0, 4)}년 ${Number(shownMonth.slice(5))}월`}
            className="kpi"
          >
            <b className="num">{fmtUsd(kpi.monthCost)}</b>
            <span>{monthName} 사용량(API 환산)</span>
          </UsageButton>
        </div>
      </div>

      <div className={`cal-layout${selected ? ' with-panel' : ''}`}>
        <div style={{ minWidth: 0 }}>
          <div className="cal-toolbar">
            <h2>{title}</h2>
            <div className="nav-btns">
              <div className="segmented">
                <button className={view === 'week' ? 'on' : ''} onClick={() => update({ view: 'week' })}>
                  주
                </button>
                <button className={view === 'month' ? 'on' : ''} onClick={() => update({ view: 'month' })}>
                  월
                </button>
                <button className={view === 'list' ? 'on' : ''} onClick={() => update({ view: 'list' })} title="세션 목록">
                  목록
                </button>
              </div>
              {!isList && (
                <>
                  <button className="btn btn-sm" onClick={() => update({ d: todayKey, day: todayKey })}>
                    오늘
                  </button>
                  <button className="btn btn-ghost icon-btn btn-sm" onClick={() => shift(-1)} aria-label="이전">
                    <Icon name="left" />
                  </button>
                  <button className="btn btn-ghost icon-btn btn-sm" onClick={() => shift(1)} aria-label="다음">
                    <Icon name="right" />
                  </button>
                </>
              )}
            </div>
          </div>
          {cal.error && <ErrorBox error={cal.error} />}
          {view === 'week' && <WeekView days={weeks[0]} byDay={byDay} todayKey={todayKey} selected={selected} onSelect={selectDay} />}
          {view === 'month' && <MonthGrid anchor={anchorDate} byDay={byDay} todayKey={todayKey} selected={selected} onSelect={selectDay} />}
          {isList && <TasksPage embedded />}
        </div>
        {selected && !isList && <DayPanel day={selected} onClose={() => update({ day: null })} />}
      </div>
    </div>
  )
}
