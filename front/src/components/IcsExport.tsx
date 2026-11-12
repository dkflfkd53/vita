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
          <a className="btn btn-primary btn-sm" href={`/api/export/calendar.ics?from=${from}&to=${to}`} download>
            .ics 내려받기
          </a>
          <ol className="subtle">
            <li>구글 캘린더 › 왼쪽 «다른 캘린더 +» › 새 캘린더 만들기(이름 Vita)</li>
            <li>설정 › 가져오기 › 이 파일 선택 › 캘린더를 «Vita»로 › 가져오기</li>
            <li>같은 기간을 다시 넣어도 같은 일정으로 봐요. 지우려면 Vita 캘린더째 삭제</li>
          </ol>
        </div>
      )}
    </div>
  )
}
