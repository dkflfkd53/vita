import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useDay, useLastJob, useRunningJob, useStartJob } from '../api/hooks'
import { api } from '../api/client'
import type { DaySession, DayTask } from '../api/types'
import { fmtDayLong, fmtDuration, fmtShort, fmtTime, fmtUsd, itemColor, today } from '../lib/format'
import { Icon } from './Icon'
import { ErrorBox, Loading, ProjectLabel } from './common'

export function DayPanel({ day, onClose }: { day: string; onClose: () => void }) {
  const { data, isLoading, error } = useDay(day)
  const jobKey = `day:${day}`
  const running = useRunningJob(jobKey)
  const last = useLastJob(jobKey)
  const start = useStartJob((d: string) => api.summarizeDay(d))
  const isToday = day === today()

  return (
    <aside className="panel">
      <div className="panel-head">
        <div>
          <h2>{fmtDayLong(day)}</h2>
          <p>
            {data ? `작업 ${fmtDuration(data.activeSec)} · 작업 ${data.tasks.length}개 · 커밋 ${data.commits.length}건 · 사용량 ${fmtUsd(data.costUsd)}` : ' '}
          </p>
        </div>
        <button className="btn btn-ghost icon-btn" onClick={onClose} aria-label="닫기">
          <Icon name="x" />
        </button>
      </div>
      <div className="panel-body">
        {isLoading && <Loading />}
        {error && <ErrorBox error={error} />}
        {data && (
          <>
            <div className="ai-box">
              <div className="ai-box-head">
                <span className="t">
                  <Icon name="sparkle" size={15} /> 하루 요약
                </span>
                {data.tasks.length > 0 && (
                  <button
                    className="btn btn-sm"
                    onClick={() => start.mutate(day)}
                    disabled={!!running || start.isPending}
                  >
                    {running ? <div className="spinner" /> : <Icon name="sparkle" size={12} />}
                    {running ? '요약 중' : data.overview ? '다시 요약' : '지금 요약'}
                  </button>
                )}
              </div>
              {data.overview ? (
                <p>{data.overview}</p>
              ) : (
                <p className="placeholder">
                  {data.tasks.length === 0
                    ? '이날은 기록된 작업이 없어요.'
                    : isToday
                      ? '오늘은 진행 중이라 자동 요약 전이에요. 필요하면 지금 요약을 눌러 주세요.'
                      : '아직 요약 전이에요. 매일 09시에 지난 날 요약이 자동으로 채워져요.'}
                </p>
              )}
              {last?.status === 'FAILED' && <p className="placeholder" style={{ color: 'var(--danger)', marginTop: 6 }}>요약 실패: {last.message}</p>}
            </div>

            {data.tasks.length > 0 && <div className="section-title">작업</div>}
            {data.tasks.map((t) => (
              <TaskBlock key={t.taskKey} task={t} />
            ))}

            {data.commits.length > 0 && (
              <>
                <div className="section-title">
                  <Icon name="commit" size={13} /> 커밋 {data.commits.length}
                </div>
                <ul className="commit-list">
                  {data.commits.map((c) => (
                    <li key={c.repo + c.hash}>
                      <span className="hash">{c.hash.slice(0, 7)}</span>
                      <span className="msg" title={c.subject}>
                        {c.subject}
                      </span>
                      <span className="repo">
                        {c.repo} · {fmtTime(c.at)}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </>
        )}
      </div>
    </aside>
  )
}

function TaskBlock({ task }: { task: DayTask }) {
  const color = itemColor(task.project, task.color)
  const summaries = task.sessions.map((s) => s.summary).filter(Boolean) as string[]
  return (
    <div className="task-block">
      <div className="task-block-head">
        <span className="color" style={{ background: color }} />
        <div style={{ minWidth: 0, flex: 1 }}>
          <Link to={`/sessions/${task.taskKey}`} className="tt">
            {task.title}
          </Link>
          <div className="meta">
            <ProjectLabel project={task.project} />
            <span>
              {fmtTime(task.firstAt)}–{fmtTime(task.lastAt)}
            </span>
            <span>· {fmtShort(task.activeSec)}</span>
            {task.costUsd > 0 && <span title="API 환산 사용량">· {fmtUsd(task.costUsd)}</span>}
          </div>
        </div>
      </div>
      {summaries.length > 0 ? (
        summaries.map((s, i) => (
          <div key={i} className="summary">
            {s}
          </div>
        ))
      ) : (
        <div className="summary none">요약 전 · 아래 요청 원문을 펼쳐 보세요</div>
      )}
      {task.sessions.map((s) => (
        <SessionFolds key={s.id} session={s} multi={task.sessions.length > 1} />
      ))}
    </div>
  )
}

function SessionFolds({ session, multi }: { session: DaySession; multi: boolean }) {
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const prefix = multi ? `${session.title} · ` : ''
  return (
    <>
      {session.prompts.length > 0 && (
        <details className="fold">
          <summary>
            <Icon name="chevron" size={12} /> {prefix}요청 {session.prompts.length}건
          </summary>
          <ul className="prompt-list">
            {session.prompts.map((p, i) => (
              <li key={i}>
                <span className="time">{fmtTime(p.at)}</span>
                <span
                  className={`txt${expanded.has(i) ? ' full' : ''}`}
                  onClick={() => setExpanded((prev) => new Set(prev).add(i))}
                >
                  {p.text}
                </span>
              </li>
            ))}
          </ul>
        </details>
      )}
      {session.edits.length > 0 && (
        <details className="fold">
          <summary>
            <Icon name="chevron" size={12} /> {prefix}수정 파일 {session.edits.length}개
          </summary>
          <ul className="file-list">
            {session.edits.map((e) => (
              <li key={e.path} title={e.path}>
                {e.repo && <span className="repo">{e.repo}/</span>}
                {e.path}
              </li>
            ))}
          </ul>
        </details>
      )}
    </>
  )
}
