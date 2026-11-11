import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useLastJob, useRunningJob, useStartJob, useTask } from '../api/hooks'
import { api } from '../api/client'
import type { SessionInfo } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel, PromptText, StatusLabel } from '../components/common'
import { UsageCompact } from '../components/UsageBreakdown'
import { addDays, fmtDateTime, fmtDayLong, fmtDuration, fmtShort, fmtTime, fmtUsd, weekKey, weekLabel, weekMonday, ymd } from '../lib/format'

export function TaskDetailPage() {
  const { key = '' } = useParams()
  const { data, isLoading, error } = useTask(key)
  // 주 사용량 — 캘린더에서 들어오면 보던 주(?week=), 아니면 이번 주
  const [params] = useSearchParams()
  const thisWeek = weekKey(new Date())
  const shownWeek = params.get('week') ?? thisWeek

  if (isLoading) return <div className="page"><Loading /></div>
  if (error || !data) return <div className="page"><ErrorBox error={error ?? '작업을 찾을 수 없어요'} /></div>
  const t = data.summary
  const wFrom = ymd(weekMonday(shownWeek))
  const wTo = ymd(addDays(weekMonday(shownWeek), 6))
  const inWeek = data.days.filter((d) => d.day >= wFrom && d.day <= wTo)
  const weekCost = inWeek.reduce((s, d) => s + d.costUsd, 0)
  const weekSec = inWeek.reduce((s, d) => s + d.activeSec, 0)
  const weekName = shownWeek === thisWeek ? '이번 주' : weekLabel(shownWeek)

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
            {data.hidden && <span className="hidden-pill">숨긴 세션</span>}
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

      {data.hidden && <HiddenNotice sessionId={data.sessions[0]?.id} />}

      <div className="stat-row six">
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
          <span>누적 사용량(API 환산)</span>
        </div>
        <div className="stat" title={`${wFrom} ~ ${wTo} · 작업 ${fmtShort(weekSec)}`}>
          <b>{fmtUsd(weekCost)}</b>
          <span>{weekName} 사용량</span>
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

          {data.models.length > 0 && (
            <div className="card">
              <div className="card-head">
                <h3>모델별 토큰·환산</h3>
              </div>
              <div className="card-body" style={{ paddingTop: 8 }}>
                <UsageCompact models={data.models} />
                <p className="ut-note">API 단가로 환산한 추정치(구독 요금과 다름) · 서브에이전트 포함 · 모델을 누르면 계산식</p>
              </div>
            </div>
          )}

          {!data.hidden && <SessionInsights taskKey={key} />}

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
                    <PromptText text={p.text} />
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

/** 숨긴 세션 상세 머리 — 합계에서 빠져 있다는 안내와 다시 보이기 */
/** 이 세션에서 나온 개선 제안 + 세션 전체를 다시 보고 개선점 찾기 */
function SessionInsights({ taskKey }: { taskKey: string }) {
  const qc = useQueryClient()
  const insights = useQuery({ queryKey: ['insights'], queryFn: () => api.insights() })
  const jobKey = `observer:scan:${taskKey}`
  const running = useRunningJob(jobKey)
  const last = useLastJob(jobKey)
  const start = useStartJob(() => api.scanTaskInsights(taskKey))
  const doneId = last?.status === 'DONE' ? last.id : null
  useEffect(() => {
    if (doneId) {
      qc.invalidateQueries({ queryKey: ['insights'] })
      qc.invalidateQueries({ queryKey: ['insight-pending'] })
    }
  }, [doneId, qc])
  const mine = (insights.data ?? []).filter((i) => i.sessions.some((s) => s.taskKey === taskKey) && i.status !== 'rejected')
  const STATUS: Record<string, string> = { proposed: '제안', adopted: '측정 중', done: '효과 확인' }
  return (
    <div className="card">
      <div className="card-head">
        <h3>개선 제안 {mine.length > 0 && mine.length}</h3>
        <button className="btn btn-sm" onClick={() => start.mutate(undefined)} disabled={!!running || start.isPending} title="이 세션을 처음부터 다시 보고, 실패·정정·되돌림에서 규칙으로 막을 만한 걸 찾아요">
          <Icon name="sparkle" size={13} />
          {running ? (running.message ?? '찾는 중') : '이 세션에서 개선점 찾기'}
        </button>
      </div>
      <div className="card-body" style={{ paddingTop: 6 }}>
        {!running && last?.status === 'DONE' && <p className="subtle" style={{ fontSize: 12, margin: '0 0 6px' }}>{last.message}</p>}
        {!running && last?.status === 'FAILED' && <p className="bad" style={{ fontSize: 12, margin: '0 0 6px' }}>{last.message}</p>}
        {mine.length === 0 ? (
          <p className="subtle" style={{ fontSize: 12.5, margin: 0 }}>이 세션에서 나온 제안이 없어요.</p>
        ) : (
          <ul className="sess-insights">
            {mine.map((i) => (
              <li key={i.id}>
                <span className="subtle">{STATUS[i.status] ?? i.status}</span>
                <Link to={`/ai?tab=improve&st=${i.status}`}>{i.title}</Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function HiddenNotice({ sessionId }: { sessionId: string | undefined }) {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const unhide = useMutation({
    mutationFn: () => api.updateSession(sessionId!, { hidden: false }),
    onSuccess: () => {
      qc.invalidateQueries()
      navigate('/sessions?view=list')
    },
  })
  return (
    <div className="callout hidden-callout">
      <Icon name="eyeOff" size={16} />
      <div>숨긴 세션이에요. 기록은 그대로 남아 있지만 캘린더·리포트·주간 합계·AI 사용 통계에서는 빠져 있어요.</div>
      {sessionId && (
        <button className="btn btn-sm" onClick={() => unhide.mutate()} disabled={unhide.isPending}>
          <Icon name="eye" size={12} /> 다시 보이기
        </button>
      )}
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
      // 이름 변경·숨김·다시 보이기로 이 작업 묶음 자체가 바뀔 수 있어 목록으로 돌아간다
      if (patch.hidden !== undefined || patch.title !== undefined) navigate('/sessions?view=list')
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
          {session.hidden ? (
            <button className="btn btn-ghost icon-btn btn-sm" title="다시 보이기 — 캘린더·리포트에 다시 들어가요" onClick={() => update.mutate({ hidden: false })}>
              <Icon name="eye" size={13} />
            </button>
          ) : (
            <button className="btn btn-ghost icon-btn btn-sm" title="숨기기 — 캘린더·리포트에서 빠져요" onClick={() => update.mutate({ hidden: true })}>
              <Icon name="eyeOff" size={13} />
            </button>
          )}
        </div>
      )}
      <div className="meta">
        {session.tool === 'codex' && <>Codex · </>}
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
