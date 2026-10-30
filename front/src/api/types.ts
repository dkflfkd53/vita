// 백엔드 com.worklog.query.Views 와 1:1. 시각은 ISO(UTC) 문자열, 날짜는 yyyy-MM-dd

export type TaskStatus = 'live' | 'recent' | 'idle'

export interface TaskSummary {
  key: string
  title: string
  projects: string[]
  sessionCount: number
  firstAt: string | null
  lastAt: string | null
  lastDay: string | null
  activeSec: number
  activeDays: number
  prompts: number
  edits: number
  status: TaskStatus
  busy: boolean
  lastSummary: string | null
  costUsd: number
  /** 이 작업을 한 도구들(claude·codex) */
  tools: string[]
}

/** 작업(세션 묶음) → 가장 최근에 연결된 할 일 */
export interface TaskWork {
  taskKey: string
  itemId: number | null
  itemTitle: string | null
  itemStatus: WorkStatus | null
  itemCategory: string | null
  lastLinkedDay: string | null
  itemCount: number
  routine: boolean
}

export interface LiveView {
  pid: number
  sessionId: string
  title: string | null
  taskKey: string | null
  project: string | null
  kind: string | null
  status: string | null
  startedAt: string | null
  statusUpdatedAt: string | null
  /** 같은 작업을 연 프로세스 수 */
  processes: number
}

export interface Overview {
  live: LiveView[]
  tasks: TaskSummary[]
  lastCollectedAt: string | null
}

export interface CalendarItem {
  taskKey: string
  title: string
  project: string | null
  activeSec: number
  costUsd: number
  /** 반복 업무(배포 등) 캘린더 색 이름, 없으면 프로젝트 색 */
  color: string | null
}

export interface CalendarDay {
  day: string
  activeSec: number
  commits: number
  hasSummary: boolean
  overview: string | null
  items: CalendarItem[]
  costUsd: number
}

export interface HeatDay {
  day: string
  activeSec: number
  commits: number
  costUsd: number
}

export interface PromptView {
  at: string
  text: string
}

export interface EditView {
  at: string
  tool: string
  repo: string | null
  path: string
}

export interface CommitView {
  repo: string
  hash: string
  at: string
  subject: string
}

export interface DaySession {
  id: string
  title: string
  summary: string | null
  activeSec: number
  firstAt: string | null
  lastAt: string | null
  prompts: PromptView[]
  edits: EditView[]
  costUsd: number
}

export interface DayTask {
  taskKey: string
  title: string
  project: string | null
  activeSec: number
  firstAt: string | null
  lastAt: string | null
  sessions: DaySession[]
  costUsd: number
  color: string | null
}

export interface DayDetail {
  day: string
  overview: string | null
  summaryAt: string | null
  activeSec: number
  tasks: DayTask[]
  commits: CommitView[]
  costUsd: number
}

export interface SessionInfo {
  id: string
  title: string
  originalTitle: string
  renamed: boolean
  cwd: string | null
  project: string | null
  gitBranch: string | null
  firstAt: string | null
  lastAt: string | null
  hidden: boolean
  /** claude | codex */
  tool: string
}

export interface TaskDay {
  day: string
  activeSec: number
  prompts: number
  edits: number
  summaries: string[]
  costUsd: number
}

export interface TaskDetail {
  summary: TaskSummary
  sessions: SessionInfo[]
  days: TaskDay[]
  recentPrompts: PromptView[]
}

export interface ProjectStat {
  project: string
  activeSec: number
  commits: number
  costUsd: number
}

export interface TaskStat {
  taskKey: string
  title: string
  project: string | null
  activeSec: number
  activeDays: number
  costUsd: number
}

export interface PeriodStats {
  from: string
  to: string
  activeSec: number
  activeDays: number
  taskCount: number
  commits: number
  prompts: number
  projects: ProjectStat[]
  tasks: TaskStat[]
  days: HeatDay[]
  costUsd: number
  models: ModelUsage[]
}

/** 토큰 합계와 API 환산 비용. unpriced = 단가표에 없는 모델의 응답 수 */
export interface UsageTotal {
  responses: number
  input: number
  output: number
  cacheRead: number
  cacheWrite5m: number
  cacheWrite1h: number
  costUsd: number
  unpriced: number
}

/** $/1M 토큰 */
export interface ModelRate {
  input: number
  output: number
  cacheRead: number
  cacheWrite5m: number
  cacheWrite1h: number
}

export interface ModelUsage {
  model: string
  priced: boolean
  usage: UsageTotal
  /** 환산에 쓴 단가, 단가표에 없는 모델이면 null */
  rate: ModelRate | null
}

/** AI 사용 화면 위 — 모델·토큰 종류·날짜별. previous = 바로 앞 기간(한 달 전체면 지난달) */
export interface UsageOverview {
  from: string
  to: string
  scope: string | null
  total: UsageTotal
  previousFrom: string
  previousTo: string
  previous: UsageTotal
  models: (ModelUsage & { tool: 'claude' | 'codex'; sessions: number })[]
  days: { day: string; models: { model: string; costUsd: number; tokens: number }[] }[]
}

export interface UsageBreakdown {
  from: string
  to: string
  total: UsageTotal
  models: ModelUsage[]
}

export type ReportKind = 'week' | 'month'

export interface ReportView {
  kind: ReportKind
  key: string
  /** 리포트 양식 id — 기본 리포트는 'default' */
  template: string
  /** 양식 이름 (기본은 '주간 리포트'·'월간 리포트') */
  title: string
  label: string
  from: string
  to: string
  content: string | null
  generatedAt: string | null
  /** 직접 고쳐 저장한 내용 */
  edited: boolean
  stats: PeriodStats
}

export interface BackupFile {
  day: string
  name: string
  size: number
  at: string
  mirrored: boolean
}

export type JobStatus = 'QUEUED' | 'RUNNING' | 'DONE' | 'FAILED'

export interface JobView {
  id: string
  key: string
  label: string
  status: JobStatus
  message: string | null
  startedAt: string | null
  finishedAt: string | null
}

// ───────── 주간 업무 (com.worklog.work.WorkViews) ─────────

export type WorkStatus = 'PLANNED' | 'IN_PROGRESS' | 'DONE' | 'ON_HOLD'
export type WorkPriority = 'HIGH' | 'MEDIUM' | 'LOW'
/** manual = 직접 지정, pin = 세션 고정, auto = AI, null = 미분류 */
export type LinkSource = 'manual' | 'pin' | 'auto' | 'inherit' | null

export interface LinkedDay {
  key: string
  sessionId: string
  day: string
  title: string
  project: string | null
  taskKey: string
  activeSec: number
  prompts: number
  summary: string | null
  source: LinkSource
  costUsd: number
}

export interface WorkItem {
  id: number
  title: string
  category: string | null
  /** 카테고리 안의 세부 분류(설정 › 카테고리 › 세부 분류 중 하나) */
  subtype: string | null
  priority: WorkPriority
  status: WorkStatus
  dueDate: string | null
  createdAt: string
  updatedAt: string
  doneAt: string | null
  memo: string | null
  planned: boolean
  activeSec: number
  sessionDays: LinkedDay[]
  costUsd: number
  sessions: ItemSession[]
  /** 이 할 일이 계획된 주들(2026-W43 …) */
  weeks: string[]
}

/** 할 일에 붙은 세션. pinned = 직접 연결(앞으로의 작업도 이 할 일로) */
export interface ItemSession {
  sessionId: string
  title: string
  project: string | null
  taskKey: string | null
  pinned: boolean
  live: boolean
  busy: boolean
  activeSec: number
  costUsd: number
  days: LinkedDay[]
}

/** "세션 추가" 목록 한 줄 */
export interface SessionCandidate {
  sessionId: string
  title: string
  project: string | null
  taskKey: string | null
  live: boolean
  busy: boolean
  kind: string | null
  lastAt: string | null
  weekActiveSec: number
  linkedItemId: number | null
  linkedItemTitle: string | null
}

export interface UnassignedSession {
  sessionId: string
  title: string
  project: string | null
  taskKey: string
  activeSec: number
  aiDecided: boolean
  confirmed: boolean
  days: LinkedDay[]
  costUsd: number
}

export interface BoardStats {
  itemCount: number
  doneCount: number
  activeSec: number
  linkedSec: number
  unassignedSec: number
  costUsd: number
  linkedCostUsd: number
}

export type BoardKind = 'week' | 'month'

/** 주·월 보드 공통. weekKey = 새 할 일·계획 외 등록이 들어갈 주 */
export interface Board {
  kind: BoardKind
  key: string
  weekKey: string
  label: string
  from: string
  to: string
  prevKey: string
  nextKey: string
  weeks: string[]
  categories: string[]
  items: WorkItem[]
  unassigned: UnassignedSession[]
  /** 기간 합계 3분 미만이라 목록에서 숨긴 짧은 흔적 수 */
  hiddenShort: number
  carryover: WorkItem[]
  stats: BoardStats
}

export interface WorkItemDetail {
  item: WorkItem
  weeks: string[]
  activeDays: number
}

export interface WorkItemSummary {
  id: number
  title: string
  category: string | null
  status: WorkStatus
}

export interface WorkItemPatch {
  title?: string
  category?: string
  priority?: WorkPriority
  status?: WorkStatus
  dueDate?: string
  memo?: string
  weekKey?: string
  /** '' = 비움 */
  subtype?: string
}

// ───────── 주간 업무 내용 (report.WorkTextService) ─────────

/** full 또는 설정의 갈래 id */
export type WorkTextPartKey = string

/** source: draft = 할 일·메모로 자동, ai = AI 작성, manual = 직접 고침 */
export interface WorkTextPart {
  part: WorkTextPartKey
  title: string
  content: string
  source: 'draft' | 'ai' | 'manual'
  generatedAt: string | null
}

export interface WorkText {
  week: string
  label: string
  parts: WorkTextPart[]
}

// ───────── 에이전트 옵저버 ─────────

export interface MetricDef {
  key: string
  label: string
  unit: string
  lowerIsBetter: boolean
  description: string
}

export interface ObsBreakdown {
  activeSec: number
  turnSec: number
  modelSec: number
  toolSec: number
  approvalSec: number
  askSec: number
  nextWaitSec: number
}

export interface ObsCounts {
  sessions: number
  prompts: number
  turns: number
  toolCalls: number
  errors: number
  rejected: number
  interrupts: number
  corrections: number
  compactions: number
  edits: number
  costUsd: number
}

export interface ObsSnapshot {
  breakdown: ObsBreakdown
  counts: ObsCounts
  metrics: Record<string, number | null>
}

export interface Observer {
  week: string
  label: string
  prevKey: string
  nextKey: string
  scope: string | null
  coverageFrom: string | null
  metricDefs: MetricDef[]
  current: ObsSnapshot
  previous: ObsSnapshot
  tools: { tool: string; calls: number; errors: number; rejected: number; totalSec: number; avgSec: number; maxSec: number }[]
  slowCommands: { command: string; calls: number; errors: number; totalSec: number; maxSec: number; sample: string }[]
  errors: { tool: string; target: string | null; error: string; count: number; lastAt: string; sessionTitle: string | null; taskKey: string | null }[]
  corrections: { text: string; at: string; sessionTitle: string | null; taskKey: string | null }[]
  rework: { path: string; edits: number; sessionTitle: string | null; taskKey: string | null }[]
  sessions: {
    sessionId: string
    taskKey: string | null
    title: string
    project: string | null
    activeSec: number
    costUsd: number
    toolCalls: number
    errors: number
    corrections: number
    compactions: number
    longestTurnSec: number
  }[]
  models: { model: string; costUsd: number; share: number }[]
  trend: { week: string; label: string; metrics: Record<string, number | null>; adopted: number[] }[]
}

export interface InsightTarget {
  key: string
  label: string
  path: string
  exists: boolean
  kind: 'claude-md' | 'memory'
}

export type InsightStatus = 'proposed' | 'adopted' | 'rejected' | 'done'

export interface Insight {
  id: number
  createdAt: string
  weekKey: string
  source: 'ai' | 'manual'
  title: string
  problem: string | null
  metric: string | null
  scope: string | null
  target: string | null
  targetLabel: string | null
  targetPath: string | null
  snippet: string | null
  status: InsightStatus
  adoptedAt: string | null
  appliedPath: string | null
  applied: boolean
  baseline: number | null
  baselineFrom: string | null
  baselineTo: string | null
  after: number | null
  afterFrom: string | null
  afterTo: string | null
  afterDays: number
  deltaPct: number | null
  verdict: string | null
  closedAt: string | null
  note: string | null
}

export interface InsightPatch {
  title?: string
  problem?: string
  metric?: string
  scope?: string
  target?: string
  snippet?: string
  note?: string
  status?: 'proposed' | 'rejected'
}

// ───────── 설정 ─────────

export interface SettingCategory {
  name: string
  color: string | null
  /** 이름을 바꿀 때만 — 예전 이름 */
  oldName?: string | null
  /** 이 카테고리 할 일의 세부 분류(예: 기능개발·오류·유지보수), 비우면 없음 */
  subtypes: string[]
}

/** repo = 폴더 경로(~/work/회사A — 그 아래 전부) 또는 저장소 폴더 이름 */
export interface ProjectRule {
  repo: string
  category: string
}

export interface RoutineRule {
  title: string
  label: string
  color: string | null
}

export interface WorkTextPartDef {
  id: string
  title: string
  /** items = 할 일 기준, commits = 커밋 기준, next = 차주 계획(다음 주 보드 + 못 끝낸 할 일 + 다음 주 마감) */
  source: 'items' | 'commits' | 'next'
  categories: string[]
  keywords: string[]
  repos: string[]
  maxLines: number | null
  guide: string | null
  example: string | null
}

export interface WorkTextConfig {
  full: WorkTextPartDef
  parts: WorkTextPartDef[]
}

/** 리포트 양식 — 리포트 화면의 버튼 하나. format 틀대로 AI 가 채운다 */
export interface ReportTemplate {
  id: string
  title: string
  kind: ReportKind
  format: string
  guide: string | null
  /** 이 카테고리 내용만 (비우면 전부) */
  categories: string[]
}

/** AI 작성 도구 — claude(claude -p) | codex(codex exec) */
export interface AiConfig {
  engine: 'claude' | 'codex'
  codexModel: string | null
}

export interface SettingsAll {
  categories: SettingCategory[]
  projects: ProjectRule[]
  routines: RoutineRule[]
  workText: WorkTextConfig
  reportTemplates: ReportTemplate[]
  ai: AiConfig
  knownRepos: string[]
  /** 규칙으로 고를 만한 폴더(본 저장소 루트와 그 상위, ~ 로 줄임) */
  knownFolders: string[]
}
