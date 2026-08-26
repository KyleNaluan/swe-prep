import { useCallback, useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'
import { APP_NAME } from './appName'
import BrandMark from './BrandMark'
import ThemeToggle from './ThemeToggle'
import Warmup from './Warmup'
import Practice from './Practice'
import Lesson from './Lesson'
import RolePicker from './RolePicker'
import Readiness from './Readiness'
import DayRibbon from './DayRibbon'
import Confetti from './Confetti'
import { clearContentHashIfOpen } from './contentNav'

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

type SessionStatus = {
  dayComplete: boolean
  completedAt: string | null
  streak: number
  // The capped repair mechanic (issue #22): a missed day can be repaired by a double
  // session (the warm-up plus a solved challenge) the next day. Both are plain facts,
  // never a currency - repairsRemainingThisMonth is a bare count, repairPending is a
  // bare boolean nudge, and neither is ever framed as a loss.
  repairsRemainingThisMonth: number
  repairPending: boolean
}

// The four surfaces: the guided daily flow (warm-up, then the day-complete landing), the
// honest readiness picture (issue #45 - the primary progress surface, not tucked behind
// Practice), the uncapped practice/browse surface (the main exercise and continuation), and
// the Learn surface (lessons, read never attempted - issue #46/#41). Readiness, Practice and
// Learn are always reachable - none is gated behind finishing the warm-up.
type Mode = 'today' | 'readiness' | 'practice' | 'learn'
type Tier = 'warmup' | 'landing'

function Session() {
  const [mode, setMode] = useState<Mode>('today')
  const [tier, setTier] = useState<Tier>('warmup')
  // Every top-level tab switch goes through this rather than setMode directly. Switching
  // to Today or Readiness unmounts whichever of Practice/Learn is currently mounted
  // without either ever popping its own content entry, so a content page left open would
  // otherwise strand the URL hash pointing at a page nothing renders anymore (see
  // contentNav.ts's clearContentHashIfOpen) - a no-op when no content page is open, so
  // every such call site can route through it with no conditional of its own. Only Learn
  // touches browser history now: it mounts with its own auto-pick (enterAutoPickedContent),
  // which replaces a still-open content entry in place rather than pushing a second one, so
  // switching *to* Learn must NOT clear first - clearing would erase the "already on a
  // content entry" signal that replace-in-place depends on and silently turn a re-entry into
  // an accumulating push. Practice contributes no history entries at all (its breadcrumb and
  // contentNav usage were retired in the full-screen redesign), so switching to or from it
  // never needs its own clear.
  const switchMode = useCallback((next: Mode) => {
    if (next === 'today' || next === 'readiness') clearContentHashIfOpen()
    setMode(next)
  }, [])
  const [status, setStatus] = useState<SessionStatus | null>(null)
  // Whether today's warm-up set came back empty. When it does there are no reps to finish
  // the day, so completing a single Practice exercise becomes the fallback that banks it -
  // an empty warm-up must never leave the day impossible to complete.
  const [warmupEmpty, setWarmupEmpty] = useState(false)
  // Bumped whenever the user changes their role focus (issue #40). It keys the warm-up so a
  // new role rebuilds the set from the newly active families rather than showing a stale one.
  const [roleVersion, setRoleVersion] = useState(0)
  const handleRoleChange = useCallback(() => {
    setWarmupEmpty(false)
    setRoleVersion((v) => v + 1)
  }, [])

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
            repairsRemainingThisMonth: prev?.repairsRemainingThisMonth ?? 0,
            repairPending: prev?.repairPending ?? false,
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

  // Practice is a full-screen workspace (captain-approved redesign, issue:
  // swe-practice-fs-build): it renders its own edge-to-edge shell - a slim top bar in
  // place of Session's `.app`/tabs chrome - rather than sitting inside `.workspace`
  // like the other three modes. Its brand icon calls `switchMode('today')` directly
  // (the same action the retired chrome's own brand icon would have taken), which is
  // how leaving the workspace is reachable with no tabs on screen; the "Practice" tab
  // button below (rendered only in the non-practice chrome) is still what enters it.
  if (mode === 'practice') {
    return <Practice onSolved={handleMainSolved} onExit={() => switchMode('today')} />
  }

  return (
    <div className="app">
      <header className="topbar session-header">
        <div className="brand">
          <span className="mark">
            <BrandMark />
          </span>
          <b className="wordmark">{APP_NAME}</b>
        </div>
        <div className="topmeta session-header-controls">
          <RolePicker onChange={handleRoleChange} />
          <DayBadge status={status} />
          <ThemeToggle />
        </div>
      </header>

      <nav className="tabs mode-tabs" aria-label="Sections">
        <button
          type="button"
          className={mode === 'today' ? 'active' : ''}
          aria-selected={mode === 'today'}
          aria-pressed={mode === 'today'}
          onClick={() => switchMode('today')}
        >
          Today
        </button>
        <button
          type="button"
          className={mode === 'readiness' ? 'active' : ''}
          aria-selected={mode === 'readiness'}
          aria-pressed={mode === 'readiness'}
          onClick={() => switchMode('readiness')}
        >
          Readiness
        </button>
        {/* Never active here: this chrome only renders when mode !== 'practice' (see
            the early return above) - Practice is a full-screen mode with no tabs of
            its own, so entering it is the only thing this button ever does. */}
        <button type="button" onClick={() => switchMode('practice')}>
          Practice
        </button>
        <button
          type="button"
          className={mode === 'learn' ? 'active' : ''}
          aria-selected={mode === 'learn'}
          aria-pressed={mode === 'learn'}
          onClick={() => switchMode('learn')}
        >
          Learn
        </button>
      </nav>

      <main className="workspace">
        {mode === 'today' ? (
          <div className="today">
            {tier === 'warmup' ? (
              <Warmup
                key={roleVersion}
                onComplete={handleWarmupComplete}
                onEmpty={handleWarmupEmpty}
              />
            ) : (
              <Landing
                status={status}
                onStartMain={() => switchMode('practice')}
                onViewReadiness={() => switchMode('readiness')}
              />
            )}
            <DayRibbon dayComplete={status?.dayComplete ?? false} />
          </div>
        ) : mode === 'readiness' ? (
          <Readiness streak={status?.streak} />
        ) : (
          <Lesson />
        )}
      </main>
    </div>
  )
}

// The at-a-glance day/streak marker in the header. It never gates anything - it is just
// the honest record (issue #7): a streak is shown, not spent, and a completed day is
// marked, so a good day looks like one.
function DayBadge({ status }: { status: SessionStatus | null }) {
  if (!status) return null
  const streakLabel = status.streak > 0 ? `${status.streak}-day streak` : null
  const repairNote = status.repairPending ? (
    <span className="repair-note">
      {' '}
      · missed yesterday - solve a challenge today to repair it (
      {status.repairsRemainingThisMonth} left this month)
    </span>
  ) : null
  if (status.dayComplete) {
    return (
      <span className="day-badge complete">
        <span className="check" aria-hidden="true">
          ✓
        </span>{' '}
        Day complete{streakLabel ? ` · ${streakLabel}` : ''}
        {repairNote}
      </span>
    )
  }
  return (
    <span className="day-badge">
      {streakLabel ? `${streakLabel} · warm up to keep it` : 'Warm up to start a streak'}
      {repairNote}
    </span>
  )
}

// The pivotal screen: the warm-up is done, so the day is done. It celebrates that first
// and unconditionally, then offers the main exercise as an explicit bonus. Nothing here
// may read as "you are not finished until you do the main" - declining is a full success.
function Landing({
  status,
  onStartMain,
  onViewReadiness,
}: {
  status: SessionStatus | null
  onStartMain: () => void
  onViewReadiness: () => void
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
        <div className="actions">
          <button type="button" className="secondary" onClick={onStartMain}>
            Actually, keep practicing
          </button>
          <button type="button" className="secondary" onClick={onViewReadiness}>
            See your readiness
          </button>
        </div>
      </section>
    )
  }

  return (
    <section className="card finish day-complete">
      <svg className="checkmark" viewBox="0 0 78 78" aria-hidden="true">
        <circle cx="39" cy="39" r="35" />
        <path d="M24 40.5 L34.5 51 L55 28" />
      </svg>
      <h1>Day complete</h1>
      <p className="status up lede">
        Your warm-up is done - that is today done.{streak > 0 ? ` ${streak}-day streak.` : ''}
      </p>
      <p className="hints-note">
        Finishing the warm-up is the whole daily goal. Everything below is a bonus; skipping
        it keeps your day complete and your streak intact.
      </p>
      <Confetti />

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

      <button type="button" className="link-button readiness-link" onClick={onViewReadiness}>
        See your readiness
      </button>
    </section>
  )
}

export default Session
