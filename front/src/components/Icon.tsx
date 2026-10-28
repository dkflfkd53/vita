// 선 굵기 1.75 의 단순 아이콘(GitHub Octicons·Lucide 느낌). 외부 아이콘 패키지 없이 필요한 것만
const PATHS = {
  calendar: 'M8 2v3M16 2v3M3.5 9h17M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v13a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V6A1.5 1.5 0 0 1 5 4.5z',
  list: 'M8 6h13M8 12h13M8 18h13M3.5 6h.01M3.5 12h.01M3.5 18h.01',
  report: 'M14 3H6.5A1.5 1.5 0 0 0 5 4.5v15A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5V8zM14 3v5h5M9 13h6M9 17h6',
  refresh: 'M20 11a8 8 0 0 0-14.9-3.9M4 4v4h4M4 13a8 8 0 0 0 14.9 3.9M20 20v-4h-4',
  sparkle: 'M12 3l1.8 5.2L19 10l-5.2 1.8L12 17l-1.8-5.2L5 10l5.2-1.8zM19 16l.7 2 2 .7-2 .7-.7 2-.7-2-2-.7 2-.7z',
  commit: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 12h6M16 12h6',
  left: 'M15 18l-6-6 6-6',
  right: 'M9 18l6-6-6-6',
  x: 'M18 6L6 18M6 6l12 12',
  eyeOff: 'M3 3l18 18M10.6 10.6a2 2 0 0 0 2.8 2.8M9.9 5.2A9.7 9.7 0 0 1 12 5c5 0 9 4.5 10 7a13 13 0 0 1-2.6 3.7M6.6 6.6C4.4 8 2.8 10.1 2 12c1 2.5 5 7 10 7a9.6 9.6 0 0 0 5.4-1.6',
  eye: 'M2 12c1-2.5 5-7 10-7s9 4.5 10 7c-1 2.5-5 7-10 7s-9-4.5-10-7zM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z',
  pencil: 'M16.5 3.5l4 4L8 20H4v-4zM14 6l4 4',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  chevron: 'M9 6l6 6-6 6',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
  file: 'M14 3H6.5A1.5 1.5 0 0 0 5 4.5v15A1.5 1.5 0 0 0 6.5 21h11a1.5 1.5 0 0 0 1.5-1.5V8zM14 3v5h5',
  message: 'M21 12a8 8 0 0 1-11.6 7.1L4 20.5l1.4-5.1A8 8 0 1 1 21 12z',
  copy: 'M9 9h10.5A1.5 1.5 0 0 1 21 10.5V20a1.5 1.5 0 0 1-1.5 1.5H10A1.5 1.5 0 0 1 8.5 20V9.5M15 9V4.5A1.5 1.5 0 0 0 13.5 3H4.5A1.5 1.5 0 0 0 3 4.5v9A1.5 1.5 0 0 0 4.5 15H8.5',
  download: 'M12 3v12M7 10l5 5 5-5M4 21h16',
  check: 'M5 12l5 5L20 7',
  terminal: 'M4 17l6-5-6-5M12 19h8',
  sun: 'M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4',
  moon: 'M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z',
  activity: 'M3 12h4l3-8 4 16 3-8h4',
  settings: 'M4 6h10M18 6h2M4 12h4M12 12h8M4 18h12M20 18h0M16 4v4M10 10v4M18 16v4',
  monitor: 'M4 4.5h16A1.5 1.5 0 0 1 21.5 6v10a1.5 1.5 0 0 1-1.5 1.5H4A1.5 1.5 0 0 1 2.5 16V6A1.5 1.5 0 0 1 4 4.5zM8 21h8M12 17.5V21',
} as const

export type IconName = keyof typeof PATHS

export function Icon({ name, size = 16, className }: { name: IconName; size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
      style={{ flex: 'none' }}
    >
      <path d={PATHS[name]} />
    </svg>
  )
}
