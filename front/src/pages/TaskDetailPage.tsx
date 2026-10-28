import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useTask } from '../api/hooks'
import { api } from '../api/client'
import type { SessionInfo } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel, StatusLabel } from '../components/common'
import { fmtDateTime, fmtDayLong, fmtDuration, fmtShort, fmtTime, fmtUsd } from '../lib/format'

export function TaskDetailPage() {
  const { key = '' } = useParams()
  const { data, isLoading, error } = useTask(key)

  if (isLoading) return <div className="page"><Loading /></div>
  if (error || !data) return <div className="page"><ErrorBox error={error ?? '작업을 찾을 수 없어요'} /></div>
  const t = data.summary

  return (
    <div className="page">
      <div className="crumb">
        <Link to="/sessions?view=list">세션</Link>
        <Icon name="chevron" size={12} />
        <span>{t.title}</span>
      </div>
      <div className="page-head">
        <div>
          <h1 style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            {t.title} <StatusLabel status={t.status} busy={t.busy} />
          </h1>
          <p style={{ display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            {t.projects.map((p) => (
              <ProjectLabel key={p} project={p} />
            ))}
            <span>
              {fmtDateTime(t.firstAt)} 시작 · 마지막 활동 {fmtDateTime(t.lastAt)}
            </span>
          </p>
        </div>
      </div>

      <div className="stat-row" style={{ gridTemplateColumns: 'repeat(5, 1fr)' }}>
        <div className="stat">
          <b>{fmtShort(t.activeSec)}</b>
          <span>작업 시간</span>
        </div>
        <div className="stat">
          <b>{t.activeDays}일</b>
          <span>활동일</span>
        </div>
        <div className="stat">
          <b>{t.prompts}</b>
          <span>요청</span>
        </div>
        <div className="stat">
          <b>{t.edits}</b>
          <span>파일 수정</span>
        </div>
        <div className="stat" title="토큰 사용량을 API 단가로 환산한 추정치">
          <b>{fmtUsd(t.costUsd)}</b>
          <span>사용량(API 환산)</span>
        </div>
      </div>

      <div className="detail-grid">
        <div className="card">
          <div className="card-head">
            <h3>날짜별 진행</h3>
          </div>
          <div className="card-body">
            <div className="timeline">
              {data.days
                .filter((d) => d.activeSec > 0 || d.prompts > 0 || d.edits > 0)
                .map((d) => (
                  <div key={d.day} className={`tl-item${d.summaries.length ? ' has' : ''}`}>
                    <div className="tl-day">
                      <Link to={`/sessions?d=${d.day}&day=${d.day}`}>{fmtDayLong(d.day)}</Link>
                      <span className="tl-meta">
                        {fmtDuration(d.activeSec)} · 요청 {d.prompts} · 수정 {d.edits} · {fmtUsd(d.costUsd)}
                      </span>
                    </div>
                    {d.summaries.length > 0 ? (
                      d.summaries.map((s, i) => (
                        <div key={i} className="tl-text">
                          {s}
                        </div>
                      ))
                    ) : (
                      <div className="tl-text none">요약 전</div>
                    )}
                  </div>
                ))}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card">
            <div className="card-head">
              <h3>세션 {data.sessions.length}</h3>
            </div>
            <div className="card-body" style={{ paddingTop: 4 }}>
              {data.sessions.map((s) => (
                <SessionRow key={s.id} session={s} />
              ))}
              <p className="subtle" style={{ fontSize: 12, margin: '10px 0 0' }}>
                이름을 다른 작업과 똑같이 바꾸면 그 작업으로 합쳐져요.
              </p>
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <h3>최근 요청</h3>
            </div>
            <div className="card-body" style={{ paddingTop: 8, maxHeight: 520, overflowY: 'auto' }}>
              <ul className="prompt-list" style={{ padding: 0 }}>
                {data.recentPrompts.map((p, i) => (
                  <li key={i} style={{ gridTemplateColumns: '64px 1fr' }}>
                    <span className="time">
                      {p.at.slice(5, 10).replace('-', '/')} {fmtTime(p.at)}
                    </span>
                    <span className="txt">{p.text}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function SessionRow({ session }: { session: SessionInfo }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [editing, setEditing] = useState(false)
  const [title, setTitle] = useState(session.title)
  const update = useMutation({
    mutationFn: (patch: { title?: string; hidden?: boolean }) => api.updateSession(session.id, patch),
    onSuccess: (_, patch) => {
      setEditing(false)
      qc.invalidateQueries()
      // 이름 변경·숨김으로 이 작업 묶음 자체가 바뀔 수 있어 목록으로 돌아간다
      if (patch.hidden || patch.title !== undefined) navigate('/sessions?view=list')
    },
  })

  return (
    <div className="session-item">
      {editing ? (
        <form
          className="row"
          onSubmit={(e) => {
            e.preventDefault()
            update.mutate({ title: title === session.originalTitle ? '' : title })
          }}
        >
          <input className="text" value={title} onChange={(e) => setTitle(e.target.value)} autoFocus />
          <button className="btn btn-sm btn-primary" type="submit" disabled={update.isPending}>
            저장
          </button>
          <button className="btn btn-sm btn-ghost" type="button" onClick={() => setEditing(false)}>
            취소
          </button>
        </form>
      ) : (
        <div className="row">
          <span className="name" title={session.title}>
            {session.title}
          </span>
          <button className="btn btn-ghost icon-btn btn-sm" title="이름 바꾸기" onClick={() => setEditing(true)}>
            <Icon name="pencil" size={13} />
          </button>
          <button className="btn btn-ghost icon-btn btn-sm" title="숨기기 — 캘린더·리포트에서 빠져요" onClick={() => update.mutate({ hidden: true })}>
            <Icon name="eyeOff" size={13} />
          </button>
        </div>
      )}
      <div className="meta">
        {session.renamed && <>원래 이름: {session.originalTitle} · </>}
        {session.gitBranch && <>{session.gitBranch} · </>}
        {fmtDateTime(session.firstAt)} ~ {fmtDateTime(session.lastAt)}
      </div>
      {session.renamed && !editing && (
        <button className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => update.mutate({ title: '' })}>
          원래 이름으로
        </button>
      )}
    </div>
  )
}
