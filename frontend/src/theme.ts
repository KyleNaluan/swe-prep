// Manual theme toggle (issue #90 follow-on - the shipped Studio redesign carried the
// token system but not the demo's "Switch theme" button). Deliberately three-state,
// not a plain light/dark flip: 'system' keeps following the OS/browser preference
// (App.css's existing `prefers-color-scheme` block), while 'light'/'dark' pin the
// same token set via the `data-theme` attribute App.css already reads - no second
// palette was added anywhere, this only decides which of the existing rules apply.
export type ThemeChoice = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'sweprep-theme'
const CHANGE_EVENT = 'sweprep-theme-change'
const ORDER: ThemeChoice[] = ['system', 'light', 'dark']

function isThemeChoice(value: string | null): value is 'light' | 'dark' {
  return value === 'light' || value === 'dark'
}

export function getStoredThemeChoice(): ThemeChoice {
  if (typeof window === 'undefined') return 'system'
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY)
    return isThemeChoice(raw) ? raw : 'system'
  } catch {
    // localStorage can throw (private mode, disabled site data) - fall back to system
    // rather than letting the toggle crash the header.
    return 'system'
  }
}

// Sets the same `data-theme` attribute App.css's dark-palette block already keys off
// (`:root[data-theme='dark']`, `:root:not([data-theme='light'])` under the media
// query), persists the choice, and tells every other mounted listener (this tab's
// other components, e.g. Practice's Monaco theme) to re-read it - a `storage` event
// alone would not fire in the tab that made the change.
export function applyThemeChoice(choice: ThemeChoice): void {
  if (typeof document !== 'undefined') {
    if (choice === 'system') {
      document.documentElement.removeAttribute('data-theme')
    } else {
      document.documentElement.setAttribute('data-theme', choice)
    }
  }
  if (typeof window !== 'undefined') {
    try {
      window.localStorage.setItem(STORAGE_KEY, choice)
    } catch {
      // Theme still applies for this load; it just won't persist across reloads.
    }
    window.dispatchEvent(new CustomEvent(CHANGE_EVENT))
  }
}

export function nextThemeChoice(choice: ThemeChoice): ThemeChoice {
  return ORDER[(ORDER.indexOf(choice) + 1) % ORDER.length]
}

// Cross-component change notifications: same-tab (CHANGE_EVENT, since applyThemeChoice
// runs in this tab) and cross-tab (the native `storage` event).
export function subscribeToThemeChange(callback: () => void): () => void {
  if (typeof window === 'undefined') return () => {}
  window.addEventListener(CHANGE_EVENT, callback)
  window.addEventListener('storage', callback)
  return () => {
    window.removeEventListener(CHANGE_EVENT, callback)
    window.removeEventListener('storage', callback)
  }
}
