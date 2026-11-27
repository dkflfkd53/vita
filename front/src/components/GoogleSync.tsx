import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { request } from '../api/client'
import { fmtDateTime } from '../lib/format'

interface GoogleStatus {
  enabled: boolean
  calendarId: string | null
  startDate: string | null
  synced: number
  running: boolean
  lastSyncAt: string | null
  lastResult: string | null
  lastError: string | null
}

/** 설정 › 구글 캘린더 자동 올리기 — Claude Code 의 구글 캘린더 연결로 1시간마다 */
export function GoogleSync() {
  const qc = useQueryClient()
  const q = useQuery({ queryKey: ['google'], queryFn: () => request<GoogleStatus>('/google'), refetchInterval: 30_000 })
  const [enabled, setEnabled] = useState(false)
  const [calendarId, setCalendarId] = useState('')
  const [start, setStart] = useState('')
  const [loaded, setLoaded] = useState(false)
  useEffect(() => {
    if (q.data && !loaded) {
      setEnabled(q.data.enabled)
      setCalendarId(q.data.calendarId ?? '')
      setStart(q.data.startDate ?? '')
      setLoaded(true)
    }
  }, [q.data, loaded])
  const refresh = () => qc.invalidateQueries({ queryKey: ['google'] })
  const save = useMutation({
    mutationFn: () => request<GoogleStatus>('/google/config', { method: 'PUT', body: JSON.stringify({ enabled, calendarId, startDate: start }) }),
    onSuccess: refresh,
  })
  const sync = useMutation({ mutationFn: () => request<{ message: string }>('/google/sync', { method: 'POST' }), onSuccess: refresh })
  const s = q.data
  return (
    <section className="card st-section" id="google">
      <div className="st-head">
        <div>
          <h2>구글 캘린더 자동 올리기</h2>
          <p>
            시간대별로 일한 덩어리와 배포를 1시간마다 구글 캘린더에 올려요. Claude Code에 연결된 구글 캘린더(claude.ai 커넥터)를 써서, Google Cloud 설정이 없어도 돼요.
            끝난 지 1시간 지난 덩어리만 올리고, 내용이 바뀌면 고치고, 합쳐져 사라지면 지워요.
          </p>
        </div>
        <div className="st-save">
          <button className="btn btn-primary btn-sm" disabled={save.isPending} onClick={() => save.mutate()}>
            저장
          </button>
        </div>
      </div>
      <div className="st-body deploy-st">
        <label className="st-check">
          <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} /> 자동으로 올리기
        </label>
        <div className="deploy-row2">
          <label>
            캘린더 ID(비우면 내 기본 캘린더)
            <input className="st-input" value={calendarId} placeholder="primary" onChange={(e) => setCalendarId(e.target.value)} />
          </label>
          <label>
            이 날짜부터만 올리기
            <input className="st-input" type="date" value={start} onChange={(e) => setStart(e.target.value)} />
          </label>
        </div>
        <div className="ob-confirm">
          <button className="btn btn-sm" disabled={sync.isPending || s?.running} onClick={() => sync.mutate()}>
            {sync.isPending || s?.running ? '올리는 중' : '지금 올리기'}
          </button>
        </div>
        <p className="subtle deploy-status">
          {s?.enabled ? '켜짐' : '꺼짐'}
          {s && ` · 관리 중인 일정 ${s.synced}개`}
          {s?.lastSyncAt && ` · 마지막 ${fmtDateTime(s.lastSyncAt)}`}
          {s?.lastResult && ` · ${s.lastResult}`}
          {s?.lastError && <span className="bad"> · {s.lastError}</span>}
          {sync.data && !s?.lastResult && ` · ${sync.data.message}`}
        </p>
      </div>
    </section>
  )
}
