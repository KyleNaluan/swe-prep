import { useCallback, useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'
import Warmup from './Warmup'
import Practice from './Practice'

// The daily session loop (issue #19) - the product the rest of the machinery serves.
//
// Three tiers, only the first required:
//   1. Warm-up (~8 reps, ~4 min). Finishing it completes the day. That alone is the goal.
//   2. Main exercise (~30 min). Optional - declining it changes nothing about the day.
//   3. Open continuation - keep going, uncapped.
//
// The small required core is the whole point: a bad, low-motivation day is still a day you
// finish. So the flow is built to make declining the main exercise feel like a full success,
// never a skipped obligation - completion is banked the instant the warm-up ends, before the
// main is ever offered, and the copy from there on treats more practice as a bonus.
//
// Opening the app puts the first rep on screen immediately: the warm-up renders at once and
// today's status (streak, whether the day is already done) is read in the background, never
// between the user and their first rep.

type SessionStatus = { dayComplete: boolean; completedAt: string | null; streak: number }

// The two surfaces: the guided daily flow (warm-up, then the day-complete landing) and the
// uncapped practice/browse surface (the main exercise and continuation). Browse is always
// reachable - it is not gated behind finishing the warm-up.
type Mode = 'today' | 'practice'
type Tier = 'warmup' | 'landing'

function Session() {
  const [mode, setMode] = useState<Mode>('today')
  const [tier, setTier] = useState<Tier>('warmup')
  const [status, setStatus] = useState<SessionStatus | null>(null)
  // Whether today's warm-up set came back empty. When it does there are no reps to finish
  // the day, so completing a single Practice exercise becomes the fallback that banks it -
  // an empty warm-up must never leave the day impossible to complete.
  const [warmupEmpty, setWarmupEmpty] = useState(false)

  const refreshStatus = useCallback(() => {
    apiFetch('/api/session')
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as SessionStatus
      })
      .then(setStatus)
      .catch(() => {
        // The badge is secondary; a failure here must never block the warm-up.
      })
  }, [])

  // Read today's status in the background. Deliberately not awaited before rendering the
  // warm-up: the "seconds to the first rep, no loading screen" criterion (issue #19) means
  // this can never sit in front of the first rep.
  useEffect(() => {
    refreshStatus()
  }, [refreshStatus])

  // Record the day as complete and refresh the streak. This is the single client path to
  // the only backend endpoint that completes a day; both the warm-up finishing and (when
  // the warm-up was empty) a Practice solve route through it. It is idempotent server-side.
  const recordDayComplete = useCallback(
    () =>
      apiFetch('/api/session/complete-warmup', { method: 'POST' })
        .then(async (response) => {
          if (!response.ok) throw new Error(await errorMessage(response))
          return (await response.json()) as SessionStatus
        })
        .then(setStatus)
        .catch(() => {
          // The completion could not be saved (backend unreachable). The practice really
          // happened, so mark the day complete locally rather than leaving the landing
          // ("Day complete") and the DayBadge (not-complete) disagreeing. The endpoint is
          // idempotent, so the next successful status read reconciles the true streak; we
          // keep the prior streak count here rather than inventing an unverified number.
          setStatus((prev) => ({
            dayComplete: true,
            completedAt: prev?.completedAt ?? null,
            streak: prev?.streak ?? 0,
          }))
        }),
    [],
  )

  // Finishing the warm-up completes the day - the whole required core. Record it (the day
  // is complete from here on, whatever happens with the optional main) and show the
  // day-complete landing.
  const handleWarmupComplete = useCallback(() => {
    void recordDayComplete()
    setTier('landing')
  }, [recordDayComplete])

  const handleWarmupEmpty = useCallback(() => setWarmupEmpty(true), [])

  // When the warm-up set was empty, solving any Practice exercise is the fallback that
  // completes the day. Otherwise the warm-up owns completion and the main stays optional,
  // so a Practice solve must not touch the day.
  const handleMainSolved = useCallback(() => {
    if (warmupEmpty && !status?.dayComplete) {
      void recordDayComplete()
    }
  }, [warmupEmpty, status?.dayComplete, recordDayComplete])

  return (
    <main className="workspace">
      <header className="session-header">
        <h1 className="wordmark">swe-prep</h1>
        <DayBadge status={status} />
      </header>

      <nav className="mode-tabs" aria-label="Sections">
        <button
          type="button"
          className={mode === 'today' ? 'active' : ''}
          aria-pressed={mode === 'today'}
          onClick={() => setMode('today')}
        >
          Today
        </button>
        <button
          type="button"
          className={mode === 'practice' ? 'active' : ''}
          aria-pressed={mode === 'practice'}
          onClick={() => setMode('practice')}
        >
          Practice
        </button>
      </nav>

      {mode === 'today' ? (
        tier === 'warmup' ? (
          <Warmup onComplete={handleWarmupComplete} onEmpty={handleWarmupEmpty} />
        ) : (
          <Landing status={status} onStartMain={() => setMode('practice')} />
        )
      ) : (
        <Practice dayComplete={status?.dayComplete ?? false} onSolved={handleMainSolved} />
      )}
    </main>
  )
}

// The at-a-glance day/streak marker in the header. It never gates anything - it is just
// the honest record (issue #7): a streak is shown, not spent, and a completed day is
// marked, so a good day looks like one.
function DayBadge({ status }: { status: SessionStatus | null }) {
  if (!status) return null
  const streakLabel = status.streak > 0 ? `${status.streak}-day streak` : null
  if (status.dayComplete) {
    return (
      <span className="day-badge complete">
        <span className="check" aria-hidden="true">
          ✓
        </span>{' '}
        Day complete{streakLabel ? ` · ${streakLabel}` : ''}
      </span>
    )
  }
  return (
    <span className="day-badge">
      {streakLabel ? `${streakLabel} · warm up to keep it` : 'Warm up to start a streak'}
    </span>
  )
}

// The pivotal screen: the warm-up is done, so the day is done. It celebrates that first
// and unconditionally, then offers the main exercise as an explicit bonus. Nothing here
// may read as "you are not finished until you do the main" - declining is a full success.
function Landing({
  status,
  onStartMain,
}: {
  status: SessionStatus | null
  onStartMain: () => void
}) {
  const [closed, setClosed] = useState(false)
  const streak = status?.streak ?? 0

  if (closed) {
    return (
      <section className="day-complete">
        <h1>Done for today</h1>
        <p className="status up">
          Nice work.{streak > 0 ? ` ${streak}-day streak.` : ''} See you tomorrow.
        </p>
        <button type="button" className="secondary" onClick={onStartMain}>
          Actually, keep practicing
        </button>
      </section>
    )
  }

  return (
    <section className="day-complete">
      <h1>Day complete</h1>
      <p className="status up">
        Your warm-up is done - that is today done.{streak > 0 ? ` ${streak}-day streak.` : ''}
      </p>
      <p className="hints-note">
        Finishing the warm-up is the whole daily goal. Everything below is a bonus; skipping
        it keeps your day complete and your streak intact.
      </p>

      <section className="main-offer">
        <h2>Optional: a main exercise</h2>
        <p>
          Feeling it today? Take on one full exercise - a coding problem, a concept, whatever
          is up next. Around 30 minutes, and entirely up to you. Afterwards you can keep going
          for as long as you like.
        </p>
        <div className="actions">
          <button type="button" onClick={onStartMain}>
            Start a main exercise
          </button>
          <button type="button" className="secondary" onClick={() => setClosed(true)}>
            I am done for today
          </button>
        </div>
      </section>
    </section>
  )
}

export default Session
