import { useEffect, useState } from 'react'
import { getStoredThemeChoice, subscribeToThemeChange } from './theme'
import { usePrefersDark } from './usePrefersDark'

// Whether the page is currently rendering dark, accounting for both the manual
// toggle (theme.ts) and, when the choice is 'system', the OS/browser preference
// (usePrefersDark). This is the one signal a component that can't just read CSS
// custom properties should use - see usePrefersDark's own doc for why Monaco needs
// it explicitly - so the manual toggle and the editor theme never disagree.
export function useEffectiveDark(): boolean {
  const prefersDark = usePrefersDark()
  const [choice, setChoice] = useState(() => getStoredThemeChoice())

  useEffect(() => subscribeToThemeChange(() => setChoice(getStoredThemeChoice())), [])

  return choice === 'system' ? prefersDark : choice === 'dark'
}
