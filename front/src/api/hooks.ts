import { useEffect, useRef } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from './client'
import { setCategoryColors } from '../lib/format'
import type { BoardKind, JobView, ReportKind, WorkItemPatch } from './types'

export const useOverview = () =>
  useQuery({ queryKey: ['overview'], queryFn: () => api.overview(), refetchInterval: 15_000 })

export const useCalendar = (month: string) =>
  useQuery({ queryKey: ['calendar', month], queryFn: () => api.calendar(month), refetchInterval: 60_000 })

export const useHeatmap = (from: string, to: string) =>
  useQuery({ queryKey: ['heatmap', from, to], queryFn: () => api.heatmap(from, to) })

export const useDay = (day: string | null) =>
  useQuery({ queryKey: ['day', day], queryFn: () => api.day(day!), enabled: !!day })

export const useTask = (key: string) => useQuery({ queryKey: ['task', key], queryFn: () => api.task(key) })

export const useHiddenSessions = () => useQuery({ queryKey: ['hidden'], queryFn: () => api.hiddenSessions() })

export const useReport = (kind: ReportKind, key: string) =>
  useQuery({ queryKey: ['report', kind, key], queryFn: () => api.report(kind, key) })

/**
 * 작업 큐 폴링. 진행 중인 작업이 있으면 2초, 없으면 15초 간격.
 * 작업이 끝나는 순간 화면 데이터(요약·리포트)를 다시 불러온다.
 */
export function useJobs() {
  const qc = useQueryClient()
  const query = useQuery({
    queryKey: ['jobs'],
    queryFn: () => api.jobs(),
    refetchInterval: (q) =>
      q.state.data?.some((j) => j.status === 'QUEUED' || j.status === 'RUNNING') ? 2_000 : 15_000,
  })
  const prev = useRef<Map<string, JobView['status']>>(new Map())
  useEffect(() => {
    const jobs = query.data ?? []
    const finished = jobs.some(
      (j) => (j.status === 'DONE' || j.status === 'FAILED') && prev.current.get(j.id) !== j.status && prev.current.has(j.id),
    )
    prev.current = new Map(jobs.map((j) => [j.id, j.status]))
    if (finished) {
      qc.invalidateQueries({ predicate: (q) => q.queryKey[0] !== 'jobs' })
    }
  }, [query.data, qc])
  return query
}

/** 작업을 넣고 곧바로 큐 폴링을 깨운다 */
export function useStartJob<T>(start: (arg: T) => Promise<JobView>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: start,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['jobs'] }),
  })
}

export function useRunningJob(key: string) {
  const { data } = useJobs()
  return data?.find((j) => j.key === key && (j.status === 'QUEUED' || j.status === 'RUNNING'))
}

export function useLastJob(key: string) {
  const { data } = useJobs()
  return data?.find((j) => j.key === key)
}

export const useBoard = (kind: BoardKind, key: string) =>
  useQuery({ queryKey: ['board', kind, key], queryFn: () => api.board(kind, key), refetchInterval: 60_000 })

export const useWorkItem = (id: number) =>
  useQuery({ queryKey: ['work-item', id], queryFn: () => api.workItem(id) })

/** 업무 쓰기 공통 — 성공하면 보드·상세·리포트를 다시 읽는다 */
export function useWorkMutation<T>(fn: (arg: T) => Promise<unknown>) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['board'] })
      qc.invalidateQueries({ queryKey: ['work-item'] })
      qc.invalidateQueries({ queryKey: ['report'] })
    },
  })
}

export const useUpdateItem = () =>
  useWorkMutation(({ id, patch }: { id: number; patch: WorkItemPatch }) => api.updateWorkItem(id, patch))

/** 사용자 설정 — 읽을 때마다 카테고리 색을 projectColor 에 넣는다 */
export function useSettings() {
  return useQuery({
    queryKey: ['settings'],
    queryFn: async () => {
      const s = await api.settings()
      setCategoryColors(s.categories)
      return s
    },
    staleTime: 60_000,
  })
}
