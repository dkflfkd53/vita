const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

/** 로컬 날짜를 yyyy-MM-dd 로 (toISOString 은 UTC 라 새벽에 하루 밀린다) */
export function ymd(d: Date): string {
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${m}-${day}`
}

export function parseYmd(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function addDays(d: Date, n: number): Date {
  const r = new Date(d)
  r.setDate(r.getDate() + n)
  return r
}

export function today(): string {
  return ymd(new Date())
}

export function monthKey(d: Date): string {
  return ymd(d).slice(0, 7)
}

/** ISO 주 키 2026-W44 */
export function weekKey(d: Date): string {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const dayNum = t.getUTCDay() || 7
  t.setUTCDate(t.getUTCDate() + 4 - dayNum)
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1))
  const week = Math.ceil(((t.getTime() - yearStart.getTime()) / 86400000 + 1) / 7)
  return `${t.getUTCFullYear()}-W${String(week).padStart(2, '0')}`
}

export function weekday(day: string): string {
  return WEEKDAYS[parseYmd(day).getDay()]
}

export function fmtDayLong(day: string): string {
  const d = parseYmd(day)
  return `${d.getMonth() + 1}월 ${d.getDate()}일 (${WEEKDAYS[d.getDay()]})`
}

/** 3725초 → "1시간 2분", 40초 → "1분 미만" */
export function fmtDuration(sec: number): string {
  if (sec < 60) return sec > 0 ? '1분 미만' : '0분'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  if (h === 0) return `${m}분`
  return m === 0 ? `${h}시간` : `${h}시간 ${m}분`
}

/** 차트·칩용 짧은 표기 "1.5h" / "40m" */
export function fmtShort(sec: number): string {
  if (sec <= 0) return '0m'
  if (sec < 3600) return `${Math.max(1, Math.round(sec / 60))}m`
  return `${(sec / 3600).toFixed(1)}h`
}

export function fmtTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export function fmtDateTime(iso: string | null): string {
  if (!iso) return ''
  const d = new Date(iso)
  return `${d.getMonth() + 1}/${d.getDate()} ${fmtTime(iso)}`
}

export function fmtRelative(iso: string | null): string {
  if (!iso) return ''
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return '방금'
  if (diff < 3600) return `${Math.floor(diff / 60)}분 전`
  if (diff < 86400) return `${Math.floor(diff / 3600)}시간 전`
  if (diff < 86400 * 30) return `${Math.floor(diff / 86400)}일 전`
  return fmtDateTime(iso)
}

// 카테고리 색은 설정에서 정한 것(setCategoryColors), 없으면 이름 해시로 배정. 실제 색은 테마별 CSS 변수(--c-*)가 정한다
let FIXED_COLORS: Record<string, string> = {}

/** 설정을 읽은 뒤 한 번 — 카테고리 이름 → 색 이름 */
export function setCategoryColors(list: { name: string; color: string | null }[]) {
  FIXED_COLORS = Object.fromEntries(list.filter((c) => c.color).map((c) => [c.name, `var(--c-${c.color})`]))
}
const PROJECT_COLORS = ['orange', 'blue', 'green', 'purple', 'yellow', 'teal', 'red', 'pink'].map((c) => `var(--c-${c})`)

export function projectColor(project: string | null | undefined): string {
  if (!project) return 'var(--c-gray)'
  if (FIXED_COLORS[project]) return FIXED_COLORS[project]
  let h = 0
  for (const ch of project) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return PROJECT_COLORS[h % PROJECT_COLORS.length]
}

const NAMED_COLORS = new Set(['gray', 'blue', 'green', 'yellow', 'red', 'purple', 'orange', 'teal', 'pink'])

/** 캘린더 막대 색 — 반복 업무(배포 등)에 따로 정한 색이 있으면 그것, 아니면 프로젝트 색 */
export function itemColor(project: string | null | undefined, color: string | null | undefined): string {
  return color && NAMED_COLORS.has(color) ? `var(--c-${color})` : projectColor(project)
}

/** 라벨용 — 글자는 그 색, 테두리·바탕은 같은 색을 옅게. CSS 변수 색에도 쓰려고 color-mix 로 섞는다 */
export function tint(color: string, border = 40, bg = 9) {
  return {
    color,
    borderColor: `color-mix(in srgb, ${color} ${border}%, transparent)`,
    background: `color-mix(in srgb, ${color} ${bg}%, transparent)`,
  }
}

/** API 환산 비용 — $0.42 / $12.4 / $1,234 */
export function fmtUsd(v: number | null | undefined): string {
  if (!v || v <= 0) return '$0'
  if (v < 1) return `$${v.toFixed(2)}`
  if (v < 100) return `$${v.toFixed(1)}`
  return `$${Math.round(v).toLocaleString('en-US')}`
}

/** 토큰 수 — 1.2K / 34.5M / 5.6B */
export function fmtTokens(n: number): string {
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)}B`
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`
  if (n >= 1e3) return `${(n / 1e3).toFixed(1)}K`
  return String(n)
}

/** 2026-W44 → 그 주 월요일 */
export function weekMonday(key: string): Date {
  const [y, w] = key.split('-W').map(Number)
  const jan4 = new Date(y, 0, 4)
  const mon = addDays(jan4, -((jan4.getDay() + 6) % 7))
  return addDays(mon, (w - 1) * 7)
}

/** 회사 주차 관례 — 그 주 목요일이 속한 달의 몇 번째 목요일인지 (9/28~10/2 → 10월 1주차) */
export function monthWeek(key: string): { year: number; month: number; n: number } {
  const thu = addDays(weekMonday(key), 3)
  return { year: thu.getFullYear(), month: thu.getMonth() + 1, n: Math.ceil(thu.getDate() / 7) }
}

/** "10월 5주차" (withYear 면 "2026년 10월 5주차") */
export function weekLabel(key: string, withYear = false): string {
  const { year, month, n } = monthWeek(key)
  return `${withYear ? `${year}년 ` : ''}${month}월 ${n}주차`
}
