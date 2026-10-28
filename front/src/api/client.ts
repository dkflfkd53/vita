import type {
  BackupFile,
  Board,
  BoardKind,
  CalendarDay,
  DayDetail,
  HeatDay,
  Insight,
  ProjectRule,
  RoutineRule,
  SettingCategory,
  AiConfig,
  SettingsAll,
  WorkTextConfig,
  UsageBreakdown,
  InsightPatch,
  InsightTarget,
  Observer,
  JobView,
  Overview,
  ReportKind,
  ReportTemplate,
  ReportView,
  SessionInfo,
  TaskDetail,
  TaskWork,
  WorkItemDetail,
  WorkItemPatch,
  SessionCandidate,
  WorkItemSummary,
  WorkText,
  WorkTextPartKey,
} from './types'

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  })
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`
    try {
      const body = await res.json()
      if (body?.message) message = body.message
    } catch {
      // 본문 없는 오류
    }
    throw new Error(message)
  }
  return res.status === 204 ? (undefined as T) : res.json()
}

export const api = {
  overview: () => request<Overview>('/overview'),
  calendar: (month: string) => request<CalendarDay[]>(`/calendar?month=${month}`),
  calendarRange: (from: string, to: string) => request<CalendarDay[]>(`/calendar?from=${from}&to=${to}`),
  usage: (from: string, to: string) => request<UsageBreakdown>(`/usage?from=${from}&to=${to}`),
  heatmap: (from: string, to: string) => request<HeatDay[]>(`/heatmap?from=${from}&to=${to}`),
  day: (day: string) => request<DayDetail>(`/days/${day}`),
  summarizeDay: (day: string) => request<JobView>(`/days/${day}/summary`, { method: 'POST' }),
  task: (key: string) => request<TaskDetail>(`/tasks/${key}`),
  hiddenSessions: () => request<SessionInfo[]>('/sessions/hidden'),
  updateSession: (id: string, patch: { title?: string; hidden?: boolean }) =>
    request<void>(`/sessions/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  report: (kind: ReportKind, key: string, template = 'default') =>
    request<ReportView>(`/reports/${kind}/${key}?template=${encodeURIComponent(template)}`),
  generateReport: (kind: ReportKind, key: string, template = 'default') =>
    request<JobView>(`/reports/${kind}/${key}?template=${encodeURIComponent(template)}`, { method: 'POST' }),
  saveReport: (kind: ReportKind, key: string, template: string, content: string) =>
    request<void>(`/reports/${kind}/${key}?template=${encodeURIComponent(template)}`, {
      method: 'PUT',
      body: JSON.stringify({ content }),
    }),
  exportReport: (kind: ReportKind, key: string, template = 'default') =>
    request<{ path: string }>(`/reports/${kind}/${key}/export?template=${encodeURIComponent(template)}`, { method: 'POST' }),
  collect: () => request<{ ran: boolean; lastCollectedAt: string }>('/collect', { method: 'POST' }),
  catchUp: () => request<JobView>('/summaries/catch-up', { method: 'POST' }),
  jobs: () => request<JobView[]>('/jobs'),
  backups: () => request<BackupFile[]>('/backups'),
  backupNow: () => request<{ local: string; mirrored: string | null; size: number }>('/backups', { method: 'POST' }),

  board: (kind: BoardKind, key: string) => request<Board>(`/${kind === 'week' ? 'weeks' : 'months'}/${key}/board`),
  classify: (week: string) => request<JobView>(`/weeks/${week}/classify?redo=true`, { method: 'POST' }),
  planItem: (week: string, id: number) => request<void>(`/weeks/${week}/items/${id}`, { method: 'POST' }),
  unplanItem: (week: string, id: number) => request<void>(`/weeks/${week}/items/${id}`, { method: 'DELETE' }),
  taskWork: () => request<TaskWork[]>('/task-work'),
  workItems: () => request<WorkItemSummary[]>('/work-items'),
  workItem: (id: number) => request<WorkItemDetail>(`/work-items/${id}`),
  createWorkItem: (body: WorkItemPatch & { title: string }) =>
    request<{ id: number }>('/work-items', { method: 'POST', body: JSON.stringify(body) }),
  updateWorkItem: (id: number, patch: WorkItemPatch) =>
    request<void>(`/work-items/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  deleteWorkItem: (id: number) => request<void>(`/work-items/${id}`, { method: 'DELETE' }),
  workItemFromSession: (sessionId: string, weekKey: string, days: string[]) =>
    request<{ id: number }>('/work-items/from-session', { method: 'POST', body: JSON.stringify({ sessionId, weekKey, days }) }),
  link: (body: { sessionId: string; days: string[]; workItemId: number | null; pin: boolean }) =>
    request<void>('/work-links', { method: 'PUT', body: JSON.stringify(body) }),
  categories: () => request<string[]>('/categories'),
  sessionCandidates: (kind: BoardKind, key: string) =>
    request<SessionCandidate[]>(`/${kind === 'week' ? 'weeks' : 'months'}/${key}/session-candidates`),
  attachSession: (id: number, sessionId: string, kind: BoardKind, key: string) =>
    request<void>(`/work-items/${id}/sessions/${sessionId}?${kind}=${key}`, { method: 'POST' }),
  detachSession: (id: number, sessionId: string) =>
    request<void>(`/work-items/${id}/sessions/${sessionId}`, { method: 'DELETE' }),
  workText: (week: string) => request<WorkText>(`/weeks/${week}/work-text`),
  saveWorkText: (week: string, part: WorkTextPartKey, content: string) =>
    request<void>(`/weeks/${week}/work-text/${part}`, { method: 'PUT', body: JSON.stringify({ content }) }),
  resetWorkText: (week: string, part: WorkTextPartKey) => request<void>(`/weeks/${week}/work-text/${part}`, { method: 'DELETE' }),
  workTextAi: (week: string, part: WorkTextPartKey | 'all') => request<JobView>(`/weeks/${week}/work-text/ai?part=${part}`, { method: 'POST' }),
  addCategory: (name: string) => request<void>('/categories', { method: 'POST', body: JSON.stringify({ name }) }),

  observer: (week: string, scope: string | null) =>
    request<Observer>(`/observer/weeks/${week}${scope ? `?scope=${scope}` : ''}`),
  observerPropose: (week: string, scope: string | null) =>
    request<JobView>(`/observer/weeks/${week}/proposals${scope ? `?scope=${scope}` : ''}`, { method: 'POST' }),
  insightTargets: () => request<InsightTarget[]>('/observer/targets'),
  insights: () => request<Insight[]>('/observer/insights'),
  createInsight: (body: InsightPatch & { title: string }, week: string) =>
    request<{ id: number }>(`/observer/insights?week=${week}`, { method: 'POST', body: JSON.stringify(body) }),
  updateInsight: (id: number, patch: InsightPatch) =>
    request<void>(`/observer/insights/${id}`, { method: 'PATCH', body: JSON.stringify(patch) }),
  deleteInsight: (id: number) => request<void>(`/observer/insights/${id}`, { method: 'DELETE' }),
  adoptInsight: (id: number, write: boolean) => request<Insight>(`/observer/insights/${id}/adopt?write=${write}`, { method: 'POST' }),
  writeInsight: (id: number) => request<void>(`/observer/insights/${id}/write`, { method: 'POST' }),
  revertInsight: (id: number) => request<void>(`/observer/insights/${id}/revert`, { method: 'POST' }),
  closeInsight: (id: number) => request<void>(`/observer/insights/${id}/close`, { method: 'POST' }),
  reopenInsight: (id: number) => request<void>(`/observer/insights/${id}/reopen`, { method: 'POST' }),
  settings: () => request<SettingsAll>('/settings'),
  saveCategories: (list: SettingCategory[]) => request<void>('/settings/categories', { method: 'PUT', body: JSON.stringify(list) }),
  saveProjects: (list: ProjectRule[]) => request<void>('/settings/projects', { method: 'PUT', body: JSON.stringify(list) }),
  saveRoutines: (list: RoutineRule[]) => request<void>('/settings/routines', { method: 'PUT', body: JSON.stringify(list) }),
  saveAi: (ai: AiConfig) => request<void>('/settings/ai', { method: 'PUT', body: JSON.stringify(ai) }),
  saveReportTemplates: (list: ReportTemplate[]) =>
    request<void>('/settings/report-templates', { method: 'PUT', body: JSON.stringify(list) }),
  saveWorkTextConfig: (config: WorkTextConfig) => request<void>('/settings/work-text', { method: 'PUT', body: JSON.stringify(config) }),
}
