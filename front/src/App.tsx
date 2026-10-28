import { Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { Sidebar } from './components/Sidebar'
import { CalendarPage } from './pages/CalendarPage'
import { ObserverPage } from './pages/ObserverPage'
import { ReportsPage } from './pages/ReportsPage'
import { SettingsPage } from './pages/SettingsPage'
import { useSettings } from './api/hooks'
import { TaskDetailPage } from './pages/TaskDetailPage'
import { WeekBoardPage } from './pages/WeekBoardPage'
import { WorkItemPage } from './pages/WorkItemPage'

function Layout() {
  // 카테고리 색을 먼저 읽어 둔다(읽히면 화면이 다시 그려진다)
  useSettings()
  return (
    <div className="app">
      <Sidebar />
      <main className="main">
        <Outlet />
      </main>
    </div>
  )
}

/** 옛 주소를 새 화면으로 — 쿼리는 그대로 넘기고 extra 를 더한다 */
function MovedTo({ path, extra }: { path: string; extra: Record<string, string> }) {
  const { search } = useLocation()
  const p = new URLSearchParams(search)
  Object.entries(extra).forEach(([k, v]) => p.set(k, v))
  return <Navigate to={`${path}?${p}`} replace />
}

/** /report — 잠깐 있던 탭 주소(?tab=weekly|report|ai)는 지금 화면으로 넘긴다 */
function ReportRoute() {
  const { search } = useLocation()
  const p = new URLSearchParams(search)
  const tab = p.get('tab')
  if (!tab) return <ReportsPage />
  const week = p.get('week')
  if (tab === 'ai') return <Navigate to={`/ai${week ? `?week=${week}` : ''}`} replace />
  p.delete('tab')
  if (tab === 'weekly' && week) {
    p.delete('week')
    p.set('kind', 'week')
    p.set('key', week)
  }
  return <Navigate to={`/report?${p}`} replace />
}

export function App() {
  return (
    <Routes>
      <Route element={<Layout />}>
        <Route index element={<WeekBoardPage />} />
        <Route path="work/:id" element={<WorkItemPage />} />
        <Route path="sessions" element={<CalendarPage />} />
        <Route path="calendar" element={<MovedTo path="/sessions" extra={{}} />} />
        <Route path="records" element={<MovedTo path="/sessions" extra={{}} />} />
        <Route path="sessions/:key" element={<TaskDetailPage />} />
        <Route path="report" element={<ReportRoute />} />
        <Route path="ai" element={<ObserverPage />} />
        <Route path="settings" element={<SettingsPage />} />
        {/* 예전 주소 */}
        <Route path="reports" element={<MovedTo path="/report" extra={{}} />} />
        <Route path="observer" element={<MovedTo path="/ai" extra={{}} />} />
      </Route>
    </Routes>
  )
}
