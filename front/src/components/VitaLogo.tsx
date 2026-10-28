import { useEffect, useState } from 'react'

// Vita 워드마크 두 가지(public/brand/vita-logo-a·e.svg 와 같은 도형) — 소문자 둥근 단일선. 글자는 currentColor(다크 모드에서 밝아짐)
//   a: i 점이 오렌지 동그라미 · e: i 점이 오렌지 잎(새싹)
// 사이드바 로고를 누르면 바뀌고(이 브라우저에 기억), 탭 아이콘도 같이 바뀐다

export type LogoVariant = 'a' | 'e'
const KEY = 'worklog-logo'

function readVariant(): LogoVariant {
  try {
    return localStorage.getItem(KEY) === 'e' ? 'e' : 'a'
  } catch {
    return 'a'
  }
}

export function useLogoVariant(): [LogoVariant, () => void] {
  const [variant, setVariant] = useState<LogoVariant>(readVariant)
  useEffect(() => {
    const link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
    if (link) link.href = `/brand/vita-mark-${variant}.svg`
  }, [variant])
  const toggle = () => {
    const next = variant === 'a' ? 'e' : 'a'
    setVariant(next)
    try {
      localStorage.setItem(KEY, next)
    } catch {
      // 기억 못 해도 이번엔 바뀐다
    }
  }
  return [variant, toggle]
}

const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 12, strokeLinecap: 'round', strokeLinejoin: 'round' } as const

export function VitaLogo({ height = 20, variant = 'a' }: { height?: number; variant?: LogoVariant }) {
  return (
    <svg viewBox="-6 10 182 98" height={height} role="img" aria-label="Vita" style={{ display: 'block' }}>
      <path d="M2 52L22 100L42 52" {...stroke} />
      <path d="M60 52V100" {...stroke} />
      <path d="M84 30V86q0 14 14 14h4" {...stroke} />
      <path d="M74 52H98" {...stroke} />
      <circle cx="138" cy="76" r="25" fill="none" stroke="currentColor" strokeWidth="12" />
      <path d="M163 52V100" {...stroke} />
      {variant === 'a' ? (
        <circle cx="60" cy="30" r="8" fill="var(--accent)" />
      ) : (
        <path d="M58 38C56 24 68 16 78 16C78 28 68 38 58 38Z" fill="var(--accent)" />
      )}
    </svg>
  )
}
