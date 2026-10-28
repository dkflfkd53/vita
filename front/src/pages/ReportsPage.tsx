import { useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { useLastJob, useReport, useRunningJob, useStartJob } from '../api/hooks'
import { api } from '../api/client'
import type { ReportKind, ReportView } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading, ProjectLabel } from '../components/common'
import { totalTokens, UsageButton, UsageCompact } from '../components/UsageBreakdown'
import { WorkTextDialog } from '../components/WorkTextDialog'
import { addDays, fmtDateTime, fmtDuration, fmtShort, fmtTokens, fmtUsd, monthKey, parseYmd, projectColor, today, weekKey, weekday } from '../lib/format'

/** 리포트 — 주간·월간 AI 업무 보고서. 주간이면 '주간보고 글' 버튼으로 사내 주간보고 글(전체 + 설정의 요약 갈래) 창을 띄운다 */
export function ReportsPage() {
  const [params, setParams] = useSearchParams()
  const kind = (params.get('kind') as ReportKind) ?? 'week'
  const key = params.get('key') ?? (kind === 'week' ? weekKey(new Date()) : today().slice(0, 7))
  const { data, isLoading, error } = useReport(kind, key)
  const [textOpen, setTextOpen] = useState(false)

  const go = (k: ReportKind, periodKey: string) => setParams({ kind: k, key: periodKey }, { replace: true })
  const shift = (n: number) => {
    if (!data) return
    if (kind === 'week') go(kind, weekKey(addDays(parseYmd(data.from), n * 7)))
    else {
      const d = parseYmd(data.from)
      d.setMonth(d.getMonth() + n)
      go(kind, monthKey(d))
    }
  }

  return (
    <div className="page">
      <div className="page-head">
        <div>
          <h1>리포트</h1>
          <p>하루 요약을 모아 주간·월간 업무 보고서로 정리해요. 사내 주간보고 글은 ‘주간보고 글’ 버튼으로 열어요.</p>
        </div>
        <div className="segmented">
          <button className={kind === 'week' ? 'on' : ''} onClick={() => go('week', weekKey(new Date()))}>
            주간
          </button>
          <button className={kind === 'month' ? 'on' : ''} onClick={() => go('month', today().slice(0, 7))}>
            월간
          </button>
        </div>
      </div>

      <div className="cal-toolbar">
        <div className="nav-btns">
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => shift(-1)} aria-label="이전">
            <Icon name="left" />
          </button>
          <h2 style={{ minWidth: 220, textAlign: 'center' }}>{data?.label ?? key}</h2>
          <button className="btn btn-ghost icon-btn btn-sm" onClick={() => shift(1)} aria-label="다음">
            <Icon name="right" />
          </button>
        </div>
        <div className="report-tools">
          {kind === 'week' && (
            <button className="btn" onClick={() => setTextOpen(true)} title="사내 주간보고에 붙일 글 — 전체 + 설정의 요약 갈래">
              <Icon name="report" size={14} /> 주간보고 글
            </button>
          )}
          {data && <ReportActions report={data} />}
        </div>
      </div>
      {textOpen && kind === 'week' && <WorkTextDialog week={key} onClose={() => setTextOpen(false)} />}

      {error && <ErrorBox error={error} />}
      {isLoading && <Loading />}
      {data && <ReportBody report={data} />}
    </div>
  )
}

function ReportActions({ report }: { report: ReportView }) {
  const jobKey = `report:${report.kind}:${report.key}`
  const running = useRunningJob(jobKey)
  const start = useStartJob(({ kind, key }: { kind: ReportKind; key: string }) => api.generateReport(kind, key))
  const [copied, setCopied] = useState(false)
  const [exported, setExported] = useState<string | null>(null)
  const exportHtml = useMutation({
    mutationFn: () => api.exportReport(report.kind, report.key),
    onSuccess: (r) => setExported(r.path),
  })

  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 8 }}>
      <div className="actions">
        {report.content && (
          <>
            <button
              className="btn"
              onClick={async () => {
                await navigator.clipboard.writeText(report.content ?? '')
                setCopied(true)
                setTimeout(() => setCopied(false), 1500)
              }}
            >
              <Icon name={copied ? 'check' : 'copy'} size={14} /> {copied ? '복사됨' : '마크다운 복사'}
            </button>
            <button className="btn" onClick={() => exportHtml.mutate()} disabled={exportHtml.isPending}>
              <Icon name="download" size={14} /> 공유용 HTML
            </button>
          </>
        )}
        <button
          className="btn btn-primary"
          onClick={() => start.mutate({ kind: report.kind, key: report.key })}
          disabled={!!running || start.isPending}
        >
          {running ? <div className="spinner" style={{ borderColor: 'rgba(255,255,255,.35)', borderTopColor: '#fff' }} /> : <Icon name="sparkle" size={14} />}
          {running ? '작성 중' : report.content ? '다시 작성' : 'AI 리포트 작성'}
        </button>
      </div>
      {running?.message && <span className="subtle" style={{ fontSize: 12 }}>{running.message}</span>}
      {exported && (
        <div className="callout" style={{ maxWidth: 560 }}>
          <Icon name="terminal" size={16} />
          <div>
            저장됨: <code>{exported}</code>
            <br />
            claude.ai 링크로 공유하려면 Claude Code 에서 <code>/worklog-publish</code> 를 실행하세요.
          </div>
        </div>
      )}
    </div>
  )
}

function ReportBody({ report }: { report: ReportView }) {
  const s = report.stats
  const navigate = useNavigate()
  const last = useLastJob(`report:${report.kind}:${report.key}`)
  const maxProject = Math.max(1, ...s.projects.map((p) => p.activeSec))
  const maxDay = Math.max(1, ...s.days.map((d) => d.activeSec))
  const todayKey = today()

  return (
    <>
      <div className="stat-row" style={{ gridTemplateColumns: 'repeat(5, 1fr)' }}>
        <div className="stat">
          <b>{fmtShort(s.activeSec)}</b>
          <span>작업 시간</span>
        </div>
        <div className="stat">
          <b>{s.activeDays}일</b>
          <span>일한 날</span>
        </div>
        <div className="stat">
          <b>{s.taskCount}</b>
          <span>작업</span>
        </div>
        <div className="stat">
          <b>{s.commits}</b>
          <span>커밋</span>
        </div>
        <div className="stat" title="세션 토큰 사용량을 API 단가로 환산한 추정치 (구독 요금과 다름)">
          <b>{fmtUsd(s.costUsd)}</b>
          <span>
            사용량(API 환산) · 토큰 {fmtTokens(s.models.reduce((t, m) => t + totalTokens(m.usage), 0))}
          </span>
        </div>
      </div>

      <div className="report-grid">
        <div className="card">
          <div className="card-body" style={{ padding: '22px 26px 28px' }}>
            {last?.status === 'FAILED' && (
              <div className="error-box" style={{ marginBottom: 14 }}>
                리포트 작성 실패: {last.message}
              </div>
            )}
            {report.content ? (
              <>
                <div className="prose">
                  <Markdown remarkPlugins={[remarkGfm]}>{report.content}</Markdown>
                </div>
                <p className="subtle" style={{ fontSize: 12, marginTop: 28 }}>
                  {fmtDateTime(report.generatedAt)} 작성 · 작업 시간은 세션 활동 기준 추정치예요
                </p>
              </>
            ) : (
              <div className="empty">
                <Icon name="sparkle" size={26} className="ai" />
                <h3>아직 작성된 리포트가 없어요</h3>
                <p>
                  오른쪽 위 <b>AI 리포트 작성</b>을 누르면 이 기간의 하루 요약을 먼저 채운 뒤 보고서를 써요.
                  <br />
                  날짜 수에 따라 1~3분쯤 걸려요.
                </p>
              </div>
            )}
          </div>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
          <div className="card">
            <div className="card-head">
              <h3>날짜별 작업 시간</h3>
            </div>
            <div className="card-body">
              <div className="day-bars">
                {s.days.filter((d) => ![0, 6].includes(parseYmd(d.day).getDay())).map((d) => (
                  <div
                    key={d.day}
                    className="d"
                    title={`${d.day} · ${fmtDuration(d.activeSec)} · 커밋 ${d.commits} · ${fmtUsd(d.costUsd)}`}
                    onClick={() => navigate(`/sessions?d=${d.day}&day=${d.day}`)}
                  >
                    <div className="b" style={{ height: `${(d.activeSec / maxDay) * 100}%`, opacity: d.day > todayKey ? 0 : undefined }} />
                    <span className="l">{report.kind === 'week' ? weekday(d.day) : Number(d.day.slice(8))}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {s.models.length > 0 && (
            <div className="card">
              <div className="card-head">
                <h3>모델별 토큰·환산</h3>
                <UsageButton from={report.from} to={report.to} title={report.label} className="btn btn-ghost btn-sm">
                  자세히
                </UsageButton>
              </div>
              <div className="card-body" style={{ paddingTop: 8 }}>
                <UsageCompact models={s.models} />
                <p className="ut-note">API 단가로 환산한 추정치(구독 요금과 다름) · 서브에이전트 포함 · 모델을 누르면 계산식</p>
              </div>
            </div>
          )}

          <div className="card">
            <div className="card-head">
              <h3>프로젝트</h3>
            </div>
            <div className="card-body">
              <div className="bars">
                {s.projects.map((p) => (
                  <div key={p.project} className="bar-row">
                    <span className="name" title={p.project}>
                      {p.project}
                    </span>
                    <div className="bar-track">
                      <div className="bar-fill" style={{ width: `${(p.activeSec / maxProject) * 100}%`, background: projectColor(p.project) }} />
                    </div>
                    <span className="v" title={`커밋 ${p.commits} · 사용량 ${fmtUsd(p.costUsd)}`}>
                      {fmtShort(p.activeSec)} · {fmtUsd(p.costUsd)}
                    </span>
                  </div>
                ))}
                {s.projects.length === 0 && <span className="subtle">기록 없음</span>}
              </div>
            </div>
          </div>

          <div className="card">
            <div className="card-head">
              <h3>작업 {s.tasks.length}</h3>
            </div>
            <div className="card-body" style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {s.tasks.slice(0, 12).map((t) => (
                <Link key={t.taskKey} to={`/sessions/${t.taskKey}`} style={{ display: 'flex', gap: 8, alignItems: 'center', fontSize: 13 }}>
                  <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{t.title}</span>
                  <ProjectLabel project={t.project} />
                  <span className="subtle num" style={{ fontSize: 12, width: 40, textAlign: 'right' }}>
                    {fmtShort(t.activeSec)}
                  </span>
                  <span className="subtle num" style={{ fontSize: 12, width: 48, textAlign: 'right' }}>
                    {fmtUsd(t.costUsd)}
                  </span>
                </Link>
              ))}
              {s.tasks.length > 12 && <span className="subtle" style={{ fontSize: 12 }}>외 {s.tasks.length - 12}개</span>}
            </div>
          </div>
        </div>
      </div>
    </>
  )
}
