// Vita 로고 = 기호 + 워드마크(public/brand/vita-logo.svg 와 같은 도형)
//   기호(B1 · 세션이 모이는 V): 왼쪽 끊긴 세 조각 = 따로 연 세션들, 오른쪽 한 줄 = 하나로 묶인 업무, 탠저린 점 = 지금
//   워드마크: 소문자 둥근 단일선 vita. 글자는 currentColor(다크 모드에서 밝아짐), 포인트색은 기호에만

const line = { fill: 'none', stroke: '#fdfdfd', strokeWidth: 6, strokeLinecap: 'round', strokeLinejoin: 'round' } as const
const stroke = { fill: 'none', stroke: 'currentColor', strokeWidth: 12, strokeLinecap: 'round', strokeLinejoin: 'round' } as const

export function VitaMark({ size = 22 }: { size?: number }) {
  return (
    <svg viewBox="0 0 64 64" width={size} height={size} aria-hidden="true" style={{ display: 'block', flex: 'none' }}>
      <rect width="64" height="64" rx="16" fill="#2f3437" />
      <path d="M16 18L18.5 22.7" {...line} />
      <path d="M22.7 30.7L25.3 35.4" {...line} />
      <path d="M29.5 43.3L32 48L46 22" {...line} />
      <circle cx="49.5" cy="14.5" r="5" fill="var(--accent)" />
    </svg>
  )
}

export function VitaLogo({ height = 20 }: { height?: number }) {
  return (
    <span style={{ display: 'flex', alignItems: 'center', gap: Math.round(height * 0.4) }} role="img" aria-label="Vita">
      <VitaMark size={Math.round(height * 1.15)} />
      <svg viewBox="-6 10 182 98" height={height} aria-hidden="true" style={{ display: 'block' }}>
        <path d="M2 52L22 100L42 52" {...stroke} />
        <path d="M60 52V100" {...stroke} />
        <circle cx="60" cy="30" r="8" fill="currentColor" />
        <path d="M84 30V86q0 14 14 14h4" {...stroke} />
        <path d="M74 52H98" {...stroke} />
        <circle cx="138" cy="76" r="25" fill="none" stroke="currentColor" strokeWidth="12" />
        <path d="M163 52V100" {...stroke} />
      </svg>
    </span>
  )
}
