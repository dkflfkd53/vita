import { useEffect, useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useSettings } from '../api/hooks'
import { api } from '../api/client'
import type { AiConfig, ProjectRule, RoutineRule, SettingCategory, SettingsAll, WorkTextConfig, WorkTextPartDef } from '../api/types'
import { Icon } from '../components/Icon'
import { ErrorBox, Loading } from '../components/common'

// 설정 — 카테고리 / 폴더 → 카테고리 / 반복 업무 세션 / 주간보고 갈래 / AI 작성. 섹션마다 따로 저장한다

const COLORS = ['gray', 'blue', 'green', 'yellow', 'red', 'purple', 'orange', 'teal', 'pink']
const COLOR_KO: Record<string, string> = {
  gray: '회색',
  blue: '파랑',
  green: '초록',
  yellow: '노랑',
  red: '빨강',
  purple: '보라',
  orange: '주황',
  teal: '청록',
  pink: '분홍',
}

export function SettingsPage() {
  const { data, isLoading, error } = useSettings()
  return (
    <div className="page settings-page">
      <div className="page-head">
        <div>
          <h1>설정</h1>
          <p>카테고리, 저장소 연결, 반복 업무 세션, 주간보고 갈래를 내 방식대로 바꿔요. 섹션마다 따로 저장돼요.</p>
        </div>
      </div>
      {error && <ErrorBox error={error} />}
      {isLoading && <Loading />}
      {data && (
        <>
          <Categories data={data} />
          <Projects data={data} />
          <Routines data={data} />
          <WorkTextParts data={data} />
          <AiEngine data={data} />
        </>
      )}
    </div>
  )
}

/** 섹션 틀 + 저장 버튼. 저장하면 화면 데이터 전부 다시 읽는다 */
function Section({
  title,
  hint,
  dirty,
  onSave,
  children,
}: {
  title: string
  hint: string
  dirty: boolean
  onSave: () => Promise<unknown>
  children: ReactNode
}) {
  const qc = useQueryClient()
  const save = useMutation({ mutationFn: onSave, onSuccess: () => qc.invalidateQueries() })
  return (
    <section className="card st-section">
      <div className="st-head">
        <div>
          <h2>{title}</h2>
          <p>{hint}</p>
        </div>
        <div className="st-save">
          {save.isError && <span className="bad">{(save.error as Error).message}</span>}
          {save.isSuccess && !dirty && <span className="subtle">저장됨</span>}
          <button className="btn btn-primary btn-sm" disabled={!dirty || save.isPending} onClick={() => save.mutate()}>
            {save.isPending ? '저장 중' : '저장'}
          </button>
        </div>
      </div>
      <div className="st-body">{children}</div>
    </section>
  )
}

function ColorSelect({ value, onChange, allowAuto }: { value: string | null; onChange: (v: string | null) => void; allowAuto?: boolean }) {
  return (
    <span className="st-color">
      <i style={{ background: value ? `var(--c-${value})` : 'var(--border-strong)' }} />
      <select value={value ?? ''} onChange={(e) => onChange(e.target.value || null)}>
        {allowAuto && <option value="">자동</option>}
        {COLORS.map((c) => (
          <option key={c} value={c}>
            {COLOR_KO[c]}
          </option>
        ))}
      </select>
    </span>
  )
}

function useDraft<T>(initial: T) {
  const [draft, setDraft] = useState<T>(initial)
  const [base, setBase] = useState(JSON.stringify(initial))
  useEffect(() => {
    setDraft(initial)
    setBase(JSON.stringify(initial))
    // 서버 값이 바뀔 때만 다시 채운다
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(initial)])
  return [draft, setDraft, JSON.stringify(draft) !== base] as const
}

// ───────── 카테고리 ─────────

function Categories({ data }: { data: SettingsAll }) {
  const [list, setList, dirty] = useDraft<SettingCategory[]>(data.categories.map((c) => ({ ...c, oldName: c.name })))
  const set = (i: number, patch: Partial<SettingCategory>) => setList(list.map((c, k) => (k === i ? { ...c, ...patch } : c)))
  const move = (i: number, d: number) => {
    const j = i + d
    if (j < 0 || j >= list.length) return
    const next = [...list]
    ;[next[i], next[j]] = [next[j], next[i]]
    setList(next)
  }
  return (
    <Section
      title="카테고리"
      hint="할 일을 묶는 큰 단위(예: 프로젝트 A, 프로젝트 B, 기타). 순서대로 업무 화면·사이드바·주간보고에 나와요. 이름을 바꾸면 할 일도 따라 바뀌어요."
      dirty={dirty}
      onSave={() => api.saveCategories(list.map((c) => ({ name: c.name.trim(), color: c.color, oldName: c.oldName ?? null })))}
    >
      {list.map((c, i) => (
        <div key={i} className="st-row">
          <ColorSelect value={c.color} onChange={(v) => set(i, { color: v })} allowAuto />
          <input className="st-input" value={c.name} placeholder="카테고리 이름" onChange={(e) => set(i, { name: e.target.value })} />
          <button className="btn btn-ghost btn-sm icon-btn" onClick={() => move(i, -1)} disabled={i === 0} aria-label="위로">
            ↑
          </button>
          <button className="btn btn-ghost btn-sm icon-btn" onClick={() => move(i, 1)} disabled={i === list.length - 1} aria-label="아래로">
            ↓
          </button>
          <button className="btn btn-ghost btn-sm icon-btn" onClick={() => setList(list.filter((_, k) => k !== i))} aria-label="삭제">
            <Icon name="x" size={13} />
          </button>
        </div>
      ))}
      <button className="btn btn-sm" onClick={() => setList([...list, { name: '', color: null, oldName: null }])}>
        + 카테고리 추가
      </button>
    </Section>
  )
}

// ───────── 저장소 → 카테고리 ─────────

function Projects({ data }: { data: SettingsAll }) {
  const [list, setList, dirty] = useDraft<ProjectRule[]>(data.projects)
  const set = (i: number, patch: Partial<ProjectRule>) => setList(list.map((r, k) => (k === i ? { ...r, ...patch } : r)))
  const used = new Set(list.map((r) => r.repo))
  return (
    <Section
      title="폴더 → 카테고리"
      hint="어느 폴더 아래에서 연 Claude 세션·커밋을 어느 카테고리로 볼지. 폴더 경로를 적으면 그 아래 저장소·하위 폴더가 전부 따라오고, 여러 규칙에 걸리면 가장 가까운(깊은) 폴더가 이겨요. 저장소 이름만 적어도 돼요. 비워 두면 전부 관리하고 이름은 저장소 이름 그대로예요."
      dirty={dirty}
      onSave={() => api.saveProjects(list.filter((r) => r.repo.trim() && r.category.trim()))}
    >
      {list.length === 0 && <p className="subtle st-empty">지금은 모든 폴더를 관리해요.</p>}
      {list.map((r, i) => (
        <div key={i} className="st-row">
          <input className="st-input" list="st-repos" value={r.repo} placeholder="폴더 경로(~/work/회사A) 또는 저장소 이름" onChange={(e) => set(i, { repo: e.target.value })} />
          <span className="subtle">→</span>
          <select className="st-input" value={r.category} onChange={(e) => set(i, { category: e.target.value })}>
            <option value="">카테고리 고르기</option>
            {data.categories.map((c) => (
              <option key={c.name} value={c.name}>
                {c.name}
              </option>
            ))}
          </select>
          <button className="btn btn-ghost btn-sm icon-btn" onClick={() => setList(list.filter((_, k) => k !== i))} aria-label="삭제">
            <Icon name="x" size={13} />
          </button>
        </div>
      ))}
      <datalist id="st-repos">
        {[...data.knownFolders, ...data.knownRepos]
          .filter((r) => !used.has(r))
          .map((r) => (
            <option key={r} value={r} />
          ))}
      </datalist>
      <button className="btn btn-sm" onClick={() => setList([...list, { repo: '', category: data.categories[0]?.name ?? '' }])}>
        + 규칙 추가
      </button>
      {data.knownFolders.length > 0 && <p className="subtle st-note">고를 만한 폴더(입력칸을 누르면 목록): {data.knownFolders.join(', ')}</p>}
    </Section>
  )
}

// ───────── 반복 업무 세션 ─────────

function Routines({ data }: { data: SettingsAll }) {
  const [list, setList, dirty] = useDraft<RoutineRule[]>(data.routines)
  const set = (i: number, patch: Partial<RoutineRule>) => setList(list.map((r, k) => (k === i ? { ...r, ...patch } : r)))
  return (
    <Section
      title="반복 업무 세션"
      hint="배포처럼 늘 하는 일을 돌리는 세션. 원래 세션 제목이 같으면 짧은 이름·색으로 보이고, 계획 외 작업·세션 추가 목록·AI 분류에서 빠져요(시간·비용 기록은 남아요)."
      dirty={dirty}
      onSave={() => api.saveRoutines(list.filter((r) => r.title.trim()))}
    >
      {list.length === 0 && <p className="subtle st-empty">없음</p>}
      {list.map((r, i) => (
        <div key={i} className="st-row">
          <input className="st-input" value={r.title} placeholder="원래 세션 제목 (예: 앱 배포 마스터)" onChange={(e) => set(i, { title: e.target.value })} />
          <span className="subtle">→</span>
          <input className="st-input short" value={r.label} placeholder="표시 이름" onChange={(e) => set(i, { label: e.target.value })} />
          <ColorSelect value={r.color} onChange={(v) => set(i, { color: v })} allowAuto />
          <button className="btn btn-ghost btn-sm icon-btn" onClick={() => setList(list.filter((_, k) => k !== i))} aria-label="삭제">
            <Icon name="x" size={13} />
          </button>
        </div>
      ))}
      <button className="btn btn-sm" onClick={() => setList([...list, { title: '', label: '', color: 'teal' }])}>
        + 반복 업무 추가
      </button>
    </Section>
  )
}

// ───────── 주간보고 갈래 ─────────

function WorkTextParts({ data }: { data: SettingsAll }) {
  const [cfg, setCfg, dirty] = useDraft<WorkTextConfig>(data.workText)
  const setPart = (i: number, patch: Partial<WorkTextPartDef>) =>
    setCfg({ ...cfg, parts: cfg.parts.map((p, k) => (k === i ? { ...p, ...patch } : p)) })
  const addPart = () => {
    let n = cfg.parts.length + 1
    while (cfg.parts.some((p) => p.id === `part-${n}`)) n++
    setCfg({
      ...cfg,
      parts: [
        ...cfg.parts,
        { id: `part-${n}`, title: '새 요약', source: 'items', categories: [], keywords: [], repos: [], maxLines: 3, guide: null, example: null },
      ],
    })
  }
  return (
    <Section
      title="주간보고 갈래"
      hint="리포트 화면의 '주간보고 글' 탭. '전체 업무 내용'은 늘 있고, 특정 프로젝트·저장소만 짧게 뽑는 요약 갈래를 더할 수 있어요. 지시와 문체 예시대로 AI 가 써요."
      dirty={dirty}
      onSave={() => api.saveWorkTextConfig(cfg)}
    >
      <div className="st-part">
        <div className="st-part-head">
          <b>{cfg.full.title}</b>
          <span className="subtle">늘 있음 · 카테고리별 할 일 전체</span>
        </div>
        <label className="st-field">
          AI 지시
          <textarea rows={5} value={cfg.full.guide ?? ''} onChange={(e) => setCfg({ ...cfg, full: { ...cfg.full, guide: e.target.value } })} />
        </label>
        <label className="st-field">
          문체 예시
          <textarea rows={8} className="mono" value={cfg.full.example ?? ''} onChange={(e) => setCfg({ ...cfg, full: { ...cfg.full, example: e.target.value } })} />
        </label>
      </div>

      {cfg.parts.map((p, i) => (
        <div key={p.id} className="st-part">
          <div className="st-part-head">
            <input className="st-input" value={p.title} onChange={(e) => setPart(i, { title: e.target.value })} placeholder="갈래 이름" />
            <span className="subtle mono">{p.id}</span>
            <button className="btn btn-ghost btn-sm" onClick={() => setCfg({ ...cfg, parts: cfg.parts.filter((_, k) => k !== i) })}>
              삭제
            </button>
          </div>
          <div className="st-grid">
            <label className="st-field">
              무엇으로
              <select value={p.source} onChange={(e) => setPart(i, { source: e.target.value as 'items' | 'commits' })}>
                <option value="items">할 일 (카테고리·키워드로 거름)</option>
                <option value="commits">커밋 (고른 저장소)</option>
              </select>
            </label>
            <label className="st-field">
              최대 줄 수
              <input type="number" min={1} max={12} value={p.maxLines ?? 3} onChange={(e) => setPart(i, { maxLines: Number(e.target.value) || 3 })} />
            </label>
          </div>
          {p.source === 'items' ? (
            <>
              <div className="st-field">
                카테고리 (비우면 전부)
                <div className="st-chips">
                  {data.categories.map((c) => (
                    <Chip
                      key={c.name}
                      on={p.categories.includes(c.name)}
                      onClick={() => setPart(i, { categories: toggle(p.categories, c.name) })}
                      label={c.name}
                    />
                  ))}
                </div>
              </div>
              <label className="st-field">
                키워드 (쉼표로, 제목·메모에 하나라도 있으면 · 비우면 전부)
                <input
                  value={p.keywords.join(', ')}
                  placeholder="예: 결제, 정산"
                  onChange={(e) => setPart(i, { keywords: e.target.value.split(',').map((k) => k.trim()).filter(Boolean) })}
                />
              </label>
            </>
          ) : (
            <div className="st-field">
              저장소 (비우면 관리하는 저장소 전부)
              <div className="st-chips">
                {data.knownRepos.map((r) => (
                  <Chip key={r} on={p.repos.includes(r)} onClick={() => setPart(i, { repos: toggle(p.repos, r) })} label={r} />
                ))}
              </div>
            </div>
          )}
          <label className="st-field">
            AI 지시 (비우면 기본)
            <textarea rows={3} value={p.guide ?? ''} placeholder="예: 머리말 없이 '- ' 줄 3개, 수치는 괄호로" onChange={(e) => setPart(i, { guide: e.target.value || null })} />
          </label>
          <label className="st-field">
            문체 예시
            <textarea rows={4} className="mono" value={p.example ?? ''} onChange={(e) => setPart(i, { example: e.target.value || null })} />
          </label>
        </div>
      ))}
      <button className="btn btn-sm" onClick={addPart}>
        + 요약 갈래 추가
      </button>
    </Section>
  )
}

// ───────── AI 작성 ─────────

function AiEngine({ data }: { data: SettingsAll }) {
  const [ai, setAi, dirty] = useDraft<AiConfig>(data.ai)
  return (
    <Section
      title="AI 작성"
      hint="하루 요약·리포트·주간보고 글·개선 제안을 어느 도구로 쓸지. 이 컴퓨터에 로그인된 CLI 를 그대로 쓰고(내 구독), 세션은 저장하지 않아요. Claude Code·Codex 세션 수집은 이 설정과 상관없이 둘 다 돼요."
      dirty={dirty}
      onSave={() => api.saveAi(ai)}
    >
      <div className="st-chips">
        <Chip on={ai.engine === 'claude'} onClick={() => setAi({ ...ai, engine: 'claude' })} label="Claude Code (claude -p)" />
        <Chip on={ai.engine === 'codex'} onClick={() => setAi({ ...ai, engine: 'codex' })} label="Codex (codex exec)" />
      </div>
      {ai.engine === 'codex' && (
        <label className="st-field">
          Codex 모델 (비우면 Codex 기본 설정)
          <input value={ai.codexModel ?? ''} placeholder="예: gpt-5.6" onChange={(e) => setAi({ ...ai, codexModel: e.target.value || null })} />
        </label>
      )}
    </Section>
  )
}

function toggle(list: string[], v: string) {
  return list.includes(v) ? list.filter((x) => x !== v) : [...list, v]
}

function Chip({ on, onClick, label }: { on: boolean; onClick: () => void; label: string }) {
  return (
    <button type="button" className={`st-chip${on ? ' on' : ''}`} onClick={onClick}>
      {label}
    </button>
  )
}
