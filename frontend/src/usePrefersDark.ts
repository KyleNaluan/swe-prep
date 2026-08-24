import { useEffect, useState } from 'react'

// Whether the OS/browser is currently asking for a dark color scheme - the same
// signal App.css's `@media (prefers-color-scheme: dark)` block reads, exposed to
// JS for the one place a stylesheet cannot reach: Monaco renders into its own
// canvas-backed DOM with a theme name, not CSS custom properties, so the editor
// would otherwise stay pinned to its light default even when the rest of the page
// (issue #90's Studio palette) has gone dark. Live-updates on a scheme change,
// same as the CSS media query already does with no JS at all.
export function usePrefersDark(): boolean {
  const query = '(prefers-color-scheme: dark)'
  const [prefersDark, setPrefersDark] = useState(
    () => typeof window !== 'undefined' && window.matchMedia?.(query).matches,
  )

  useEffect(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return
    const mql = window.matchMedia(query)
    const onChange = (event: MediaQueryListEvent) => setPrefersDark(event.matches)
    mql.addEventListener('change', onChange)
    return () => mql.removeEventListener('change', onChange)
  }, [])

  return prefersDark
}
