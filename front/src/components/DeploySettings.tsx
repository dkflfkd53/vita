import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { DeployConfig, DeploySource } from '../api/types'
import { useSettings } from '../api/hooks'
import { fmtDateTime } from '../lib/format'

/** 설정 › 배포 기록 — CI/CD(GitLab) 배포 잡을 보드·캘린더·주간보고 글에 보이게 */
export function DeploySettings() {
  const qc = useQueryClient()
  const { data: settings } = useSettings()
  const q = useQuery({ queryKey: ['deploy-config'], queryFn: () => api.deployConfig() })
  const [cfg, setCfg] = useState<DeployConfig | null>(null)
  useEffect(() => {
    if (q.data && !cfg) setCfg(q.data.config)
  }, [q.data, cfg])
  const save = useMutation({
    mutationFn: (c: DeployConfig) => api.saveDeployConfig(c),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['deploy-config'] })
      qc.invalidateQueries({ queryKey: ['deploys'] })
    },
  })
  const sync = useMutation({ mutationFn: () => api.deploySync(), onSuccess: () => qc.invalidateQueries({ queryKey: ['deploys'] }) })
  if (!cfg) return null
  const setSource = (i: number, p: Partial<DeploySource>) =>
    setCfg({ ...cfg, sources: cfg.sources.map((s, k) => (k === i ? { ...s, ...p } : s)) })
  const result = save.data ?? sync.data
  const err = result?.error ?? q.data?.lastError
  return (
    <section className="card st-section" id="deploy">
      <div className="st-head">
        <div>
          <h2>배포 기록 (CI/CD)</h2>
          <p>GitLab 파이프라인의 배포 잡을 10분마다 읽어 세션 캘린더(배포한 날에 막대)·날짜 상세·주간보고 글에 넣어요. 토큰은 read_api 권한이면 충분해요.</p>
        </div>
        <div className="st-save">
          <button className="btn btn-sm" disabled={sync.isPending} onClick={() => sync.mutate()}>
            {sync.isPending ? '가져오는 중' : '지금 가져오기'}
          </button>
          <button className="btn btn-primary btn-sm" disabled={save.isPending} onClick={() => save.mutate(cfg)}>
            {save.isPending ? '저장 중' : '저장'}
          </button>
        </div>
      </div>
      <div className="st-body deploy-st">
        <div className="deploy-row2">
          <label>
            GitLab 주소
            <input className="st-input" value={cfg.baseUrl ?? ''} placeholder="https://gitlab.example.com" onChange={(e) => setCfg({ ...cfg, baseUrl: e.target.value })} />
          </label>
          <label>
            토큰
            <input className="st-input" type="password" value={cfg.token ?? ''} placeholder="glpat-… (read_api)" onChange={(e) => setCfg({ ...cfg, token: e.target.value })} />
          </label>
        </div>
        <div className="deploy-src deploy-src-head">
          <span>프로젝트 경로</span>
          <span>잡 이름</span>
          <span>보드에 보일 이름</span>
          <span>짧은 이름</span>
          <span>카테고리</span>
          <span />
        </div>
        {cfg.sources.map((s, i) => (
          <div key={i} className="deploy-src">
            <input className="st-input" value={s.project} placeholder="group/my-app" onChange={(e) => setSource(i, { project: e.target.value })} />
            <input className="st-input" value={s.job} placeholder="deploy:prod" onChange={(e) => setSource(i, { job: e.target.value })} />
            <input className="st-input" value={s.label ?? ''} placeholder="운영 배포" onChange={(e) => setSource(i, { label: e.target.value })} />
            <input className="st-input" value={s.shortName ?? ''} placeholder="back" onChange={(e) => setSource(i, { shortName: e.target.value })} />
            <select className="st-input" value={s.category ?? ''} onChange={(e) => setSource(i, { category: e.target.value || null })}>
              <option value="">(카테고리 없음)</option>
              {(settings?.categories ?? []).map((c) => (
                <option key={c.name} value={c.name}>
                  {c.name}
                </option>
              ))}
            </select>
            <button className="btn btn-ghost btn-sm" onClick={() => setCfg({ ...cfg, sources: cfg.sources.filter((_, k) => k !== i) })}>
              빼기
            </button>
          </div>
        ))}
        <button
          className="btn btn-ghost btn-sm"
          onClick={() => setCfg({ ...cfg, sources: [...cfg.sources, { project: '', job: '', label: null, category: null, shortName: null }] })}
        >
          + 원천 추가
        </button>
        <p className="subtle deploy-status">
          {err ? <span className="bad">{err}</span> : result ? `새로 ${result.added}건 · ` : ''}
          {(result?.syncedAt ?? q.data?.lastSyncAt) && `마지막 가져오기 ${fmtDateTime((result?.syncedAt ?? q.data?.lastSyncAt)!)}`}
        </p>
      </div>
    </section>
  )
}
