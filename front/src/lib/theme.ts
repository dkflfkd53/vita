import { useEffect, useState } from 'react'

export type ThemeChoice = 'light' | 'dark' | 'system'

const KEY = 'worklog-theme'
const DARK = '(prefers-color-scheme: dark)'

/** 저장소가 막힌 환경(시크릿 창 등)에서도 라이트로 동작한다 */
export function readTheme(): ThemeChoice {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'dark' || v === 'system' ? v : 'light'
  } catch {
    return 'light'
  }
}

function apply(choice: ThemeChoice) {
  const dark = choice === 'dark' || (choice === 'system' && window.matchMedia(DARK).matches)
  document.documentElement.dataset.theme = dark ? 'dark' : 'light'
}

export function useTheme(): [ThemeChoice, (t: ThemeChoice) => void] {
  const [theme, setTheme] = useState<ThemeChoice>(readTheme)
  useEffect(() => {
    apply(theme)
    try {
      localStorage.setItem(KEY, theme)
    } catch {
      // 저장 못 해도 이번 화면엔 적용된다
    }
    if (theme !== 'system') return
    const mq = window.matchMedia(DARK)
    const onChange = () => apply('system')
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [theme])
  return [theme, setTheme]
}
