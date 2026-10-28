import { useId } from 'react'

// Vita 워드마크(public/brand/vita-logo.svg 와 같은 도형) — 글자는 currentColor 라 다크 모드에서 밝아지고,
// a 는 기둥 없이 오렌지 단면(껍질 고리 + 과육 6조각). 과육 사이 틈은 마스크로 뚫어 바탕이 비친다
export function VitaLogo({ height = 20 }: { height?: number }) {
  const mask = `vita-pulp-${useId().replace(/:/g, '')}`
  return (
    <svg viewBox="-4 2 198 106" height={height} role="img" aria-label="Vita" style={{ display: 'block' }}>
      <defs>
        <mask id={mask}>
          <circle cx="158" cy="69" r="25.5" fill="white" />
          <g stroke="black" strokeWidth="2.6">
            <line x1="158" y1="40" x2="158" y2="98" />
            <line x1="158" y1="40" x2="158" y2="98" transform="rotate(60 158 69)" />
            <line x1="158" y1="40" x2="158" y2="98" transform="rotate(120 158 69)" />
          </g>
          <circle cx="158" cy="69" r="3.6" fill="black" />
        </mask>
      </defs>
      <g fill="currentColor">
        <polygon points="0,10 14,10 32,80 50,10 64,10 40,100 24,100" />
        <rect x="72" y="38" width="12" height="62" rx="1" />
        <circle cx="78" cy="19" r="8" />
        <rect x="100" y="20" width="12" height="80" rx="1" />
        <rect x="92" y="38" width="30" height="11" rx="1" />
      </g>
      <path fill="var(--accent)" fillRule="evenodd" d="M158 37a32 32 0 1 0 0.01 0zM158 40.5a28.5 28.5 0 1 1 -0.01 0z" />
      <circle cx="158" cy="69" r="25.5" mask={`url(#${mask})`} style={{ fill: 'color-mix(in srgb, var(--accent) 70%, #fff)' }} />
    </svg>
  )
}
