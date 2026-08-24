import { useEffect, useState } from 'react'
import { applyThemeChoice, getStoredThemeChoice, nextThemeChoice, type ThemeChoice } from './theme'
import { usePrefersDark } from './usePrefersDark'

// The demo's `themeBtn` (direction-c.html), carried over into the shipped Studio
// header (issue #90 follow-on) - styled with the token system's existing `.iconbtn`
// rather than a new class. Cycles system -> light -> dark -> system; the icon and
// tooltip always name both the choice and its current effective appearance, since
// 'system' alone doesn't say whether that currently reads light or dark.
const ICON: Record<ThemeChoice, string> = { system: '◐', light: '☀', dark: '☾' }
const CHOICE_LABEL: Record<ThemeChoice, string> = {
  system: 'Matching system',
  light: 'Light',
  dark: 'Dark',
}

export default function ThemeToggle() {
  const [choice, setChoice] = useState<ThemeChoice>(() => getStoredThemeChoice())
  const prefersDark = usePrefersDark()

  // index.html's inline script already applies the stored choice before paint (no
  // flash); this effect keeps `data-theme` in sync whenever the choice changes here.
  useEffect(() => {
    applyThemeChoice(choice)
  }, [choice])

  const effective = choice === 'system' ? (prefersDark ? 'dark' : 'light') : choice
  const title = `Theme: ${CHOICE_LABEL[choice]} (currently ${effective}) - click to switch`

  return (
    <button
      type="button"
      className="iconbtn"
      onClick={() => setChoice((current) => nextThemeChoice(current))}
      title={title}
      aria-label={title}
    >
      {ICON[choice]}
    </button>
  )
}
