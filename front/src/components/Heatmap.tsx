import { useEffect, useRef } from 'react'
import type { HeatDay } from '../api/types'
import { fmtDuration, parseYmd } from '../lib/format'

export function heatLevel(sec: number): number {
  if (sec <= 0) return 0
  if (sec < 3600) return 1
  if (sec < 3 * 3600) return 2
  if (sec < 5 * 3600) return 3
  return 4
}

/** GitHub 잔디(평일만) */
export function Heatmap({
  days,
  today,
  selected,
  onSelect,
}: {
  days: HeatDay[]
  today: string
  selected: string | null
  onSelect: (day: string) => void
}) {
  // 평일만 세로 5칸(월~금). days 는 월요일부터 이어진 날짜여야 한다
  const workdays = days.filter((d) => {
    const dow = parseYmd(d.day).getDay()
    return dow !== 0 && dow !== 6
  })
  const weeks: HeatDay[][] = []
  for (let i = 0; i < workdays.length; i += 5) weeks.push(workdays.slice(i, i + 5))
  // 칸이 좁아 다 안 들어가면 최근 주가 보이도록 오른쪽 끝에서 시작
  const scroller = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = scroller.current
    if (el) el.scrollLeft = el.scrollWidth
  }, [days.length])

  return (
    <div ref={scroller} style={{ overflowX: 'auto' }}>
      <div className="heat-months">
        {weeks.map((w, i) => {
          const first = parseYmd(w[0].day)
          const prev = i > 0 ? parseYmd(weeks[i - 1][0].day) : null
          const show = !prev || prev.getMonth() !== first.getMonth()
          return <span key={w[0].day}>{show ? `${first.getMonth() + 1}월` : ''}</span>
        })}
      </div>
      <div className="heatmap">
        {weeks.map((w) => (
          <div key={w[0].day} className="heat-col">
            {w.map((d) => (
              <div
                key={d.day}
                className={`heat-cell l${heatLevel(d.activeSec)}${d.day > today ? ' future' : ''}${d.day === selected ? ' sel' : ''}`}
                title={`${d.day} · ${fmtDuration(d.activeSec)} · 커밋 ${d.commits}`}
                onClick={() => onSelect(d.day)}
              />
            ))}
          </div>
        ))}
      </div>
      <div className="heat-legend">
        적음
        {[0, 1, 2, 3, 4].map((l) => (
          <div key={l} className={`heat-cell l${l}`} />
        ))}
        많음
      </div>
    </div>
  )
}
