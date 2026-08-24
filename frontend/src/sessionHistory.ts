import { apiFetch, errorMessage } from './api'

// The shared shape GET /api/session/history returns (backend session.DayHistory,
// issue #90): one projection of day_completion serving both the day ribbon (Today,
// the trailing 30 days) and the year-record grid (Readiness, the whole response) -
// grafted from Direction A/C's mockups. Oldest first.
export type DayHistory = {
  date: string
  completed: boolean
  doubleSession: boolean
  bridged: boolean
}

export function fetchSessionHistory(): Promise<DayHistory[]> {
  return apiFetch('/api/session/history').then(async (response) => {
    if (!response.ok) throw new Error(await errorMessage(response))
    const body: unknown = await response.json()
    // Defends against a test double (or a genuinely broken backend) that answers every
    // fetch with some other endpoint's shape - both the ribbon and the year grid treat
    // that as "history unavailable" (render nothing) rather than crashing on `.map`.
    if (!Array.isArray(body)) throw new Error('Malformed session history response')
    return body as DayHistory[]
  })
}
