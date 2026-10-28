import { NavLink, Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useJobs, useOverview, useSettings } from '../api/hooks'
import { api } from '../api/client'
import type { LiveView, TaskWork } from '../api/types'
import { fmtRelative } from '../lib/format'
import { useTheme, type ThemeChoice } from '../lib/theme'
import { Icon } from './Icon'
import { VitaLogo, useLogoVariant } from './VitaLogo'
import { StatusDot } from './common'

export function Sidebar() {
  const [logo, toggleLogo] = useLogoVariant()
  const { data } = useOverview()
  const { data: jobs } = useJobs()
  const qc = useQueryClient()
  const backups = useQuery({ queryKey: ['backups'], queryFn: () => api.backups(), refetchInterval: 600_000 })
  const taskWork = useQuery({ queryKey: ['task-work'], queryFn: () => api.taskWork(), refetchInterval: 60_000 })
  const settings = useSettings()
  const backup = useMutation({
    mutationFn: () => api.backupNow(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['backups'] }),
  })
  const lastBackup = backups.data?.[0]
  const collect = useMutation({
    mutationFn: () => api.collect(),
    onSuccess: () => qc.invalidateQueries(),
  })
  const running = jobs?.find((j) => j.status === 'RUNNING') ?? jobs?.find((j) => j.status === 'QUEUED')
  const failed = !running
    ? jobs?.find((j) => j.status === 'FAILED' && j.finishedAt && Date.now() - Date.parse(j.finishedAt) < 600_000)
    : undefined
  const live = data?.live ?? []
  const busy = live.filter((l) => l.status === 'busy').length

  return (
    <aside className="sidebar">
      <div className="brand">
        <button type="button" className="brand-logo" onClick={toggleLogo} title="눌러서 로고 바꿔 보기 (A 동그라미 점 / E 새싹 점)">
          <VitaLogo height={24} variant={logo} />
        </button>
        <small>에이전트 세션 기반 업무 관리</small>
      </div>

      <nav className="nav">
        <NavLink to="/" end>
          <Icon name="check" /> 업무
        </NavLink>
        <NavLink to="/sessions">
          <Icon name="calendar" /> 세션
        </NavLink>
        <NavLink to="/report">
          <Icon name="report" /> 리포트
        </NavLink>
        <NavLink to="/ai">
          <Icon name="activity" /> AI 사용
        </NavLink>
        <NavLink to="/settings">
          <Icon name="settings" /> 설정
        </NavLink>
      </nav>

      <div className="side-section">
        <div className="side-title">
          <span>실행 중인 세션</span>
          <span className="num">
            {busy > 0 ? `${busy} 작업 중 · ` : ''}
            {live.length}
          </span>
        </div>
        <div className="side-list">
          {live.length === 0 && <div className="live-item subtle">열린 세션 없음</div>}
          {groupLive(live, taskWork.data ?? [], (settings.data?.categories ?? []).map((c) => c.name)).map((g) => (
            <div key={g.key} className="live-group">
              <div className="live-group-head">
                {g.title}
                <span className="num">{g.items.length}</span>
              </div>
              {g.items.map(({ live: l, item }) => {
                const body = (
                  <>
                    <StatusDot variant={l.status === 'busy' ? 'busy' : 'live'} />
                    <div>
                      <div className="t">{l.title ?? '새 세션'}</div>
                      <div className="s" title={item ?? undefined}>
                        {item ?? l.project ?? '-'}
                        {l.kind === 'bg' ? ' · 백그라운드' : ''}
                        {l.processes > 1 ? ` · 창 ${l.processes}개` : ''} · {fmtRelative(l.statusUpdatedAt)}
                      </div>
                    </div>
                  </>
                )
                return l.taskKey ? (
                  <Link key={l.pid} to={`/sessions/${l.taskKey}`} className="live-item">
                    {body}
                  </Link>
                ) : (
                  <div key={l.pid} className="live-item">
                    {body}
                  </div>
                )
              })}
            </div>
          ))}
        </div>
      </div>

      <div className="side-footer">
        {running && (
          <div className="job-line" title={running.message ?? running.label}>
            <div className="spinner" />
            <span>{running.message ?? running.label}</span>
          </div>
        )}
        {failed && (
          <div className="job-line" style={{ color: 'var(--danger)' }} title={failed.message ?? ''}>
            <Icon name="x" size={13} />
            <span>
              {failed.label} 실패: {failed.message}
            </span>
          </div>
        )}
        <div className="row">
          <ThemeSwitch />
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => backup.mutate()}
            disabled={backup.isPending}
            title={
              backup.isError
                ? `백업 실패: ${(backup.error as Error).message}`
                : lastBackup
                  ? `마지막 백업 ${lastBackup.name}${lastBackup.mirrored ? ' · iCloud 암호화 사본 있음' : ' · iCloud 사본 없음'}\n누르면 지금 백업`
                  : '아직 백업 없음 — 누르면 지금 백업'
            }
          >
            <Icon name={backup.isError ? 'x' : 'download'} size={13} />
            {backup.isPending
              ? '백업 중'
              : lastBackup
                ? `백업 ${fmtRelative(lastBackup.at)}${lastBackup.mirrored ? ' ☁' : ''}`
                : '백업 없음'}
          </button>
        </div>
        <div className="row">
          <span>마지막 수집 {data?.lastCollectedAt ? fmtRelative(data.lastCollectedAt) : '-'}</span>
          <button
            className="btn btn-ghost btn-sm"
            onClick={() => collect.mutate()}
            disabled={collect.isPending}
            title="세션 기록을 지금 다시 읽는다 (평소엔 10분마다 자동)"
          >
            <Icon name="refresh" size={13} /> {collect.isPending ? '수집 중' : '지금 수집'}
          </button>
        </div>
      </div>
    </aside>
  )
}

const THEMES: { key: ThemeChoice; icon: 'sun' | 'moon' | 'monitor'; label: string }[] = [
  { key: 'light', icon: 'sun', label: '라이트' },
  { key: 'dark', icon: 'moon', label: '다크' },
  { key: 'system', icon: 'monitor', label: '시스템 설정 따라가기' },
]

function ThemeSwitch() {
  const [theme, setTheme] = useTheme()
  return (
    <div className="theme-switch" role="radiogroup" aria-label="테마">
      {THEMES.map((t) => (
        <button
          key={t.key}
          className={theme === t.key ? 'on' : ''}
          onClick={() => setTheme(t.key)}
          title={t.label}
          aria-label={t.label}
          role="radio"
          aria-checked={theme === t.key}
        >
          <Icon name={t.icon} size={14} />
        </button>
      ))}
    </div>
  )
}

interface LiveGroup {
  key: string
  title: string
  rank: number
  items: { live: LiveView; item: string | null }[]
}

/** 실행 중인 세션을 연결된 할 일의 카테고리로(설정의 카테고리 순서 + 나머지). 나머지 = 할 일 없는 세션·반복 업무. 묶음 안은 작업 중 → 최근 순 */
function groupLive(live: LiveView[], work: TaskWork[], order: string[]): LiveGroup[] {
  const byTask = new Map(work.map((w) => [w.taskKey, w]))
  const groups = new Map<string, LiveGroup>()
  for (const l of live) {
    const w = l.taskKey ? byTask.get(l.taskKey) : undefined
    const category = w && !w.routine && w.itemId ? (w.itemCategory ?? '나머지') : '나머지'
    let g = groups.get(category)
    if (!g) {
      const rank = order.indexOf(category)
      g = { key: category, title: category, rank: category === '나머지' ? 99 : rank < 0 ? 50 : rank, items: [] }
      groups.set(category, g)
    }
    g.items.push({ live: l, item: w && !w.routine && w.itemId ? w.itemTitle : null })
  }
  const busyFirst = (a: LiveView, b: LiveView) =>
    (a.status === 'busy' ? 0 : 1) - (b.status === 'busy' ? 0 : 1) || (b.statusUpdatedAt ?? '').localeCompare(a.statusUpdatedAt ?? '')
  for (const g of groups.values()) g.items.sort((a, b) => busyFirst(a.live, b.live))
  return [...groups.values()].sort((a, b) => a.rank - b.rank || a.title.localeCompare(b.title))
}
