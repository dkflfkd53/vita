import type { CalendarDay } from '../api/types'

/** 한 주(평일 5칸) 안에서 같은 세션 묶음이 이어진 날들을 하나의 막대로 */
export interface Segment {
  taskKey: string
  title: string
  project: string | null
  /** 반복 업무 색 이름(없으면 프로젝트 색) */
  color: string | null
  start: number // 0~4(월~금)
  end: number // 포함
  lane: number
  activeSec: number
  costUsd: number
  perDay: { day: string; activeSec: number; costUsd: number }[]
  /** 주 경계 너머로 이어짐 — 막대 끝을 평평하게 그린다 */
  contLeft: boolean
  contRight: boolean
}

/**
 * days = 그 주 평일(yyyy-MM-dd), prevDay·nextDay = 앞뒤 근무일(전주 금·다음 주 월) — 주말을 건너뛰어 이어짐을 판정한다.
 * 줄 배정: 긴 막대·작업 시간이 큰 것부터 위 줄을 차지하게 해서, 줄 수를 제한해도 중요한 게 남는다.
 */
export function layoutWeek(days: string[], byDay: Map<string, CalendarDay>, prevDay: string, nextDay: string): Segment[] {
  const n = days.length
  const byTask = new Map<string, { title: string; project: string | null; color: string | null; secs: (number | null)[]; costs: number[] }>()
  days.forEach((day, i) => {
    for (const it of byDay.get(day)?.items ?? []) {
      let t = byTask.get(it.taskKey)
      if (!t) {
        t = { title: it.title, project: it.project, color: it.color ?? null, secs: Array(n).fill(null), costs: Array(n).fill(0) }
        byTask.set(it.taskKey, t)
      }
      t.secs[i] = it.activeSec
      t.costs[i] = it.costUsd
    }
  })
  const has = (day: string, key: string) => (byDay.get(day)?.items ?? []).some((it) => it.taskKey === key)

  const segments: Segment[] = []
  for (const [taskKey, t] of byTask) {
    let i = 0
    while (i < n) {
      if (t.secs[i] === null) {
        i++
        continue
      }
      let j = i
      while (j + 1 < n && t.secs[j + 1] !== null) j++
      const perDay = days.slice(i, j + 1).map((day, k) => ({ day, activeSec: t.secs[i + k] ?? 0, costUsd: t.costs[i + k] }))
      segments.push({
        taskKey,
        title: t.title,
        project: t.project,
        color: t.color,
        start: i,
        end: j,
        lane: 0,
        activeSec: perDay.reduce((s, d) => s + d.activeSec, 0),
        costUsd: perDay.reduce((s, d) => s + d.costUsd, 0),
        perDay,
        contLeft: i === 0 && has(prevDay, taskKey),
        contRight: j === n - 1 && has(nextDay, taskKey),
      })
      i = j + 1
    }
  }

  segments.sort((a, b) => b.end - b.start - (a.end - a.start) || b.activeSec - a.activeSec || a.start - b.start)
  const lanes: boolean[][] = []
  for (const seg of segments) {
    let lane = 0
    for (; ; lane++) {
      if (!lanes[lane]) lanes[lane] = Array(n).fill(false)
      let free = true
      for (let d = seg.start; d <= seg.end; d++) if (lanes[lane][d]) free = false
      if (free) break
    }
    for (let d = seg.start; d <= seg.end; d++) lanes[lane][d] = true
    seg.lane = lane
  }
  return segments.sort((a, b) => a.lane - b.lane || a.start - b.start)
}

/** 줄 제한(maxLanes) 때문에 가려진 막대 수를 날짜 칸별로 */
export function hiddenPerDay(segments: Segment[], maxLanes: number, n: number): number[] {
  const hidden = Array(n).fill(0)
  for (const s of segments) {
    if (s.lane < maxLanes) continue
    for (let d = s.start; d <= s.end; d++) hidden[d]++
  }
  return hidden
}
