import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import { fmtDateTime } from '../lib/format'

// 버전 업데이트 — 새 커밋이 있으면 받아서 다시 설치하고 재시작. 서버가 새 버전으로 다시 뜨면 화면을 새로고침한다

function useUpdate() {
  const q = useQuery({ queryKey: ['update'], queryFn: () => api.updateStatus(), refetchInterval: 600_000 })
  const [waiting, setWaiting] = useState<string | null>(null)
  const apply = useMutation({ mutationFn: () => api.updateApply(), onSuccess: () => setWaiting(q.data?.current?.sha ?? '?') })
  // 업데이트 중엔 4초마다 — 서버가 내려갔다 새 커밋으로 다시 뜨면 새로고침
  useEffect(() => {
    if (!waiting) return
    const t = window.setInterval(async () => {
      try {
        const s = await api.updateStatus()
        if (s.current && s.current.sha !== waiting && !s.updating) window.location.reload()
      } catch {
        /* 재시작 중 */
      }
    }, 4000)
    return () => window.clearInterval(t)
  }, [waiting])
  return { q, apply, waiting }
}

/** 사이드바 아래 — 새 버전이 있을 때만 버튼 하나 */
export function UpdateNotice() {
  const { q, apply, waiting } = useUpdate()
  const [open, setOpen] = useState(false)
  const s = q.data
  if (waiting) {
    return (
      <div className="update-note">
        <div className="spinner" /> 업데이트 중 — 다시 켜지면 새로고침돼요
      </div>
    )
  }
  if (!s || !s.available || s.behind === 0) return null
  return (
    <div className="update-note">
      <button className="btn btn-primary btn-sm update-btn" onClick={() => setOpen((v) => !v)}>
        새 버전 {s.behind}개 · 업데이트
      </button>
      {open && (
        <div className="update-pop">
          <ul>
            {s.commits.slice(0, 6).map((c) => (
              <li key={c.sha}>{c.subject}</li>
            ))}
            {s.behind > 6 && <li className="subtle">외 {s.behind - 6}개</li>}
          </ul>
          {s.dirty ? (
            <p className="bad">저장소에 커밋 안 한 변경이 있어 자동 업데이트를 멈췄어요</p>
          ) : (
            <button className="btn btn-primary btn-sm" disabled={apply.isPending} onClick={() => apply.mutate()}>
              {apply.isPending ? '받는 중' : '받아서 다시 시작'}
            </button>
          )}
          {apply.isError && <p className="bad">{(apply.error as Error).message}</p>}
        </div>
      )}
    </div>
  )
}

/** 설정 화면 — 지금 버전, 지금 확인, 새 커밋 목록과 업데이트 */
export function UpdateSection() {
  const qc = useQueryClient()
  const { q, apply, waiting } = useUpdate()
  const check = useMutation({ mutationFn: () => api.updateCheck(), onSuccess: (s) => qc.setQueryData(['update'], s) })
  const s = q.data
  return (
    <section className="card st-section" id="version">
      <div className="st-head">
        <div>
          <h2>버전</h2>
          <p>새 버전이 있으면 받아서 다시 빌드하고 재시작해요(1~2분). 6시간마다 저절로 확인해요.</p>
        </div>
        <div className="st-save">
          <button className="btn btn-sm" disabled={check.isPending || !!waiting} onClick={() => check.mutate()}>
            {check.isPending ? '확인 중' : '지금 확인'}
          </button>
          {s?.available && s.behind > 0 && !s.dirty && (
            <button className="btn btn-primary btn-sm" disabled={apply.isPending || !!waiting} onClick={() => apply.mutate()}>
              {waiting ? '업데이트 중' : `새 버전 ${s.behind}개 받기`}
            </button>
          )}
        </div>
      </div>
      <div className="st-body update-body">
        {!s && q.isLoading && <p className="subtle">확인 중</p>}
        {s && (
          <>
            <p>
              {s.current ? (
                <>
                  지금 <code>{s.current.sha}</code> · {s.current.subject} <span className="subtle">({fmtDateTime(s.current.date)})</span>
                </>
              ) : (
                '지금 버전을 읽지 못했어요'
              )}
            </p>
            {s.available && s.behind === 0 && !s.error && <p className="good">최신이에요</p>}
            {s.behind > 0 && (
              <ul>
                {s.commits.map((c) => (
                  <li key={c.sha}>
                    <code>{c.sha}</code> {c.subject}
                  </li>
                ))}
              </ul>
            )}
            {s.dirty && <p className="bad">저장소({s.repoDir})에 커밋 안 한 변경이 있어 자동 업데이트를 멈췄어요</p>}
            {s.error && <p className="bad">{s.error}</p>}
            {waiting && <p className="subtle">업데이트 중 — 다시 켜지면 새로고침돼요</p>}
            {apply.isError && <p className="bad">{(apply.error as Error).message}</p>}
            <p className="subtle">
              마지막 확인 {fmtDateTime(s.checkedAt)}
              {s.repoDir && ` · ${s.repoDir}`}
              {s.branch && ` · ${s.branch}`}
            </p>
          </>
        )}
      </div>
    </section>
  )
}
