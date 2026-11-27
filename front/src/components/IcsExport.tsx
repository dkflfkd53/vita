import { useState } from 'react'
import { addDays, today, ymd } from '../lib/format'

/** 지난달 1주차 월요일 — 그 달 첫 목요일이 든 주(회사 주차 관례) */
function lastMonthWeek1(): string {
  const now = new Date()
  const first = new Date(now.getFullYear(), now.getMonth() - 1, 1)
  const thu = addDays(first, (4 - first.getDay() + 7) % 7)
  return ymd(addDays(thu, -3))
}

/** 세션 캘린더 → .ics 내려받기(구글 캘린더 «가져오기»용) */
export function IcsExport() {
  const [open, setOpen] = useState(false)
  const [from, setFrom] = useState(lastMonthWeek1)
  const [to, setTo] = useState(today)
  const [mode, setMode] = useState<'day' | 'time'>('time')
  return (
    <div className="ics-export">
      <button className="btn btn-ghost btn-sm" onClick={() => setOpen((v) => !v)} title="구글 캘린더 등에 넣을 .ics 파일">
        캘린더로 내보내기
      </button>
      {open && (
        <div className="ics-pop">
          <div className="ics-row">
            <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} />
            <span>~</span>
            <input type="date" value={to} onChange={(e) => setTo(e.target.value)} />
          </div>
          <div className="segmented sm" role="radiogroup" aria-label="일정 모양">
            <button role="radio" aria-checked={mode === 'time'} className={mode === 'time' ? 'on' : ''} onClick={() => setMode('time')} title="실제 일한 시간 덩어리(15분 넘게 비면 끊음)를 시간 지정 일정으로">
              시간대별
            </button>
            <button role="radio" aria-checked={mode === 'day'} className={mode === 'day' ? 'on' : ''} onClick={() => setMode('day')} title="세션 캘린더처럼 하루 종일 막대">
              하루 종일
            </button>
          </div>
          <a className="btn btn-primary btn-sm" href={`/api/export/calendar.ics?from=${from}&to=${to}&mode=${mode}`} download>
            .ics 내려받기
          </a>
          <ol className="subtle">
            <li>구글 캘린더 › 왼쪽 «다른 캘린더 +» › 새 캘린더 만들기({mode === 'time' ? '이름 Vita 시간대' : '이름 Vita'})</li>
            <li>설정 › 가져오기 › 이 파일 선택 › 캘린더를 {mode === 'time' ? '«Vita 시간대»' : '«Vita»'}로 › 가져오기</li>
            <li>같은 기간을 다시 넣어도 같은 일정으로 봐요. 지우려면 Vita 캘린더째 삭제</li>
          </ol>
        </div>
      )}
    </div>
  )
}
