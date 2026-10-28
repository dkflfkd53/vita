import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useUpdateItem, useWorkItem, useWorkMutation } from '../api/hooks'
import { api } from '../api/client'
import type { LinkedDay, WorkItemPatch } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading } from '../components/common'
import { CategorySelect, DueDateInput, MemoEditor, PrioritySelect, SOURCE_LABEL, StatusSelect, TextCell } from '../components/workFields'
import { fmtDateTime, fmtDayLong, fmtDuration, fmtShort, fmtUsd, weekLabel } from '../lib/format'

export function WorkItemPage() {
  const { id: idParam = '' } = useParams()
  const id = Number(idParam)
  const { data, isLoading, error } = useWorkItem(id)
  const categories = useQuery({ queryKey: ['categories'], queryFn: () => api.categories() })
  const update = useUpdateItem()
  const navigate = useNavigate()
  const remove = useWorkMutation(() => api.deleteWorkItem(id))

  if (isLoading) return <div className="page"><Loading /></div>
  if (error || !data) return <div className="page"><ErrorBox error={error ?? '업무를 찾을 수 없어요'} /></div>
  const item = data.item
  const save = (patch: WorkItemPatch) => update.mutate({ id, patch })

  const byDay = new Map<string, LinkedDay[]>()
  for (const d of item.sessionDays) {
    if (!byDay.has(d.day)) byDay.set(d.day, [])
    byDay.get(d.day)!.push(d)
  }

  return (
    <div className="page" style={{ maxWidth: 1100 }}>
      <div className="crumb">
        <Link to="/">주간 업무</Link>
        <Icon name="chevron" size={12} />
        <span>{item.title}</span>
      </div>

      <TextCell value={item.title} onCommit={(v) => save({ title: v })} className="page-title-input" required />

      <div className="props">
        <span className="prop-k">상태</span>
        <div>
          <StatusSelect value={item.status} onSave={save} />
        </div>
        <span className="prop-k">우선순위</span>
        <div>
          <PrioritySelect value={item.priority} onSave={save} />
        </div>
        <span className="prop-k">카테고리</span>
        <div>
          <CategorySelect value={item.category} categories={categories.data ?? []} onSave={save} />
        </div>
        <span className="prop-k">마감일</span>
        <div>
          <DueDateInput value={item.dueDate} status={item.status} onSave={save} />
        </div>
        <span className="prop-k">계획된 주</span>
        <div className="week-chips">
          {data.weeks.length === 0 && <span className="subtle">없음</span>}
          {data.weeks.map((w) => (
            <Link key={w} to={`/?week=${w}`}>
              {weekLabel(w, true)}
            </Link>
          ))}
        </div>
        <span className="prop-k">만든 날</span>
        <div className="subtle">
          {fmtDateTime(item.createdAt)}
          {item.doneAt && ` · 완료 ${fmtDateTime(item.doneAt)}`}
        </div>
      </div>

      <div className="detail-memo">
        <MemoEditor value={item.memo} onSave={(memo) => save({ memo })} placeholder="메모 — 세부 할 일, 진행 상황, 결정 사항, 참고 링크…" />
      </div>

      <div className="stat-row">
        <div className="stat">
          <b>{fmtShort(item.activeSec)}</b>
          <span>투입 시간(세션 기준)</span>
        </div>
        <div className="stat">
          <b>{data.activeDays}일</b>
          <span>작업한 날</span>
        </div>
        <div className="stat">
          <b>{item.sessionDays.length}</b>
          <span>연결된 세션 작업</span>
        </div>
        <div className="stat" title="연결된 세션의 토큰 사용량을 API 단가로 환산한 추정치">
          <b>{fmtUsd(item.costUsd)}</b>
          <span>사용량(API 환산)</span>
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <h3>진행 기록</h3>
        </div>
        <div className="card-body">
          {byDay.size === 0 && (
            <p className="subtle" style={{ margin: 0 }}>
              아직 연결된 세션 작업이 없어요. 주간 업무 화면의 "계획 외 · 미분류"에서 연결하거나, AI 자동 분류를 돌려 보세요.
            </p>
          )}
          <div className="timeline">
            {[...byDay.entries()].map(([day, list]) => (
              <div key={day} className={`tl-item${list.some((d) => d.summary) ? ' has' : ''}`}>
                <div className="tl-day">
                  <Link to={`/sessions?d=${day}&day=${day}`}>{fmtDayLong(day)}</Link>
                  <span className="tl-meta">
                    {fmtDuration(list.reduce((s, d) => s + d.activeSec, 0))} · {fmtUsd(list.reduce((s, d) => s + d.costUsd, 0))}
                  </span>
                </div>
                {list.map((d) => (
                  <div key={d.key} className="tl-text">
                    <span className="subtle" style={{ fontSize: 12 }}>
                      <Link to={`/sessions/${d.taskKey}`}>{d.title}</Link>
                      {d.source && <span className={`src src-${d.source}`} style={{ marginLeft: 6 }}>{SOURCE_LABEL[d.source]}</span>}
                    </span>
                    <div className={d.summary ? '' : 'subtle'}>{d.summary ?? `요약 전 · 요청 ${d.prompts}건`}</div>
                  </div>
                ))}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div style={{ marginTop: 24 }}>
        <button
          className="btn btn-ghost btn-sm"
          style={{ color: 'var(--danger)' }}
          onClick={() => {
            if (window.confirm(`"${item.title}" 업무를 삭제할까요? 연결 기록도 함께 지워져요.`)) {
              remove.mutate(undefined, { onSuccess: () => navigate('/') })
            }
          }}
        >
          업무 삭제
        </button>
      </div>
    </div>
  )
}
