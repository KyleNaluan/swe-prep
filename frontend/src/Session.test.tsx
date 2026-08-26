import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Session from './Session'

// Monaco needs a real canvas jsdom cannot give it; stand it in with a textarea so a code
// main exercise still renders (the session must not assume an algorithm, so this proves a
// code problem and a concept render through the same flow).
vi.mock('@monaco-editor/react', () => ({
  default: ({ defaultValue }: { defaultValue?: string }) => (
    <textarea aria-label="editor" defaultValue={defaultValue} />
  ),
}))

const WARMUP_SET = [
  { id: 'rep-1', title: 'Rep One', domain: 'fundamentals', difficulty: 'EASY', form: 'REP' },
]

const REP = {
  id: 'rep-1',
  title: 'Rep One',
  statement: 'Which pattern fits?',
  domain: 'fundamentals',
  difficulty: 'EASY',
  response: { kind: 'choice', options: ['A', 'B'] },
  hasExplanation: false,
}

// A non-algorithm concept challenge and an algorithm coding challenge: the main defaults
// to a CHALLENGE of any domain, never "the first algorithm".
const CATALOG = [
  { id: 'concept-main', title: 'Concept Main', domain: 'backend', difficulty: 'EASY', form: 'CHALLENGE' },
  { id: 'code-main', title: 'Code Main', domain: 'algorithms', difficulty: 'MEDIUM', form: 'CHALLENGE' },
]

const CONCEPT_MAIN = {
  id: 'concept-main',
  title: 'Concept Main',
  statement: 'What does an index do?',
  domain: 'backend',
  difficulty: 'EASY',
  response: { kind: 'choice', options: ['Speeds reads', 'Slows reads'] },
  hasExplanation: false,
}

const CODE_MAIN = {
  id: 'code-main',
  title: 'Code Main',
  statement: 'Reverse the array.',
  domain: 'algorithms',
  difficulty: 'MEDIUM',
  response: { kind: 'code', language: 'java', stub: 'class Solution {}' },
  hasExplanation: false,
}

type Calls = { completeWarmup: number; abandons: string[] }

// Drives every fetch the session makes. `status` is what GET /api/session returns before
// the warm-up is finished; completing it flips the reported status to complete.
function mockFetch(
  calls: Calls,
  options: { warmup?: unknown[]; failComplete?: boolean; repairPending?: boolean } = {},
) {
  let dayComplete = false
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url)
    const method = init?.method ?? 'GET'
    if (href.endsWith('/api/reps/warmup')) {
      return { ok: true, json: async () => options.warmup ?? WARMUP_SET } as Response
    }
    if (href.endsWith('/api/role')) {
      // The role picker (issue #40) reads its status on mount and PUTs on a change; the session
      // tests do not exercise role switching, so a stable default status is enough.
      return {
        ok: true,
        json: async () => ({
          presets: [{ id: 'everything', label: 'Everything', families: [] }],
          activeFamilies: [],
          currentPreset: 'everything',
          chosen: false,
        }),
      } as Response
    }
    if (href.endsWith('/api/session/complete-warmup')) {
      calls.completeWarmup += 1
      // Simulate an unreachable backend: the completion cannot be saved.
      if (options.failComplete) {
        return { ok: false, status: 500, json: async () => ({ error: 'backend down' }) } as Response
      }
      dayComplete = true
      return {
        ok: true,
        json: async () => ({ dayComplete: true, completedAt: '2026-08-07T09:00:00Z', streak: 3 }),
      } as Response
    }
    if (href.endsWith('/api/session')) {
      return {
        ok: true,
        json: async () => ({
          dayComplete,
          completedAt: null,
          streak: dayComplete ? 3 : 2,
          repairsRemainingThisMonth: 2,
          repairPending: options.repairPending ?? false,
        }),
      } as Response
    }
    if (href.endsWith('/api/readiness')) {
      return {
        ok: true,
        json: async () => ({
          checksToCriterion: { achieved: 1, total: 3 },
          solvedCold: { achieved: 0, total: 1 },
          conceptsCovered: { achieved: 0, total: 1 },
          selfCheckExplainedCount: 0,
          families: [],
        }),
      } as Response
    }
    if (href.endsWith('/api/exercises')) {
      return { ok: true, json: async () => CATALOG } as Response
    }
    if (href.includes('/api/exercises/rep-1')) return { ok: true, json: async () => REP } as Response
    if (href.includes('/api/exercises/concept-main'))
      return { ok: true, json: async () => CONCEPT_MAIN } as Response
    if (href.includes('/api/exercises/code-main'))
      return { ok: true, json: async () => CODE_MAIN } as Response
    if (href.endsWith('/abandon')) {
      calls.abandons.push(href)
      return { ok: true, json: async () => ({ id: 'att' }) } as Response
    }
    if (href.endsWith('/api/attempts')) {
      if (method === 'POST') return { ok: true, json: async () => ({ id: 'att-1' }) } as Response
      return { ok: true, json: async () => [] } as Response
    }
    if (href.endsWith('/submissions')) {
      return {
        ok: true,
        json: async () => ({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }),
      } as Response
    }
    throw new Error(`unexpected fetch to ${method} ${href}`)
  })
}

// Selects an exercise through the TreeBrowser inside Practice's "Problem List" overlay
// sidebar (captain-approved full-screen redesign, issue: swe-practice-fs-build - the
// breadcrumb this helper used to drive is retired on Practice): open the sidebar,
// search narrows it to a cross-domain match, then the matching row is clicked -
// closing the sidebar automatically, the same as a real user's path.
async function selectExercise(title: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Problem List' }))
  const sidebar = await screen.findByRole('dialog', { name: 'Problem list' })
  fireEvent.change(within(sidebar).getByLabelText('Find a problem'), { target: { value: title } })
  fireEvent.click(within(sidebar).getByRole('button', { name: new RegExp(title) }))
}

// Answers the one warm-up rep correctly and finishes the set, landing on day-complete.
async function finishWarmup() {
  fireEvent.click(await screen.findByLabelText('A'))
  fireEvent.click(screen.getByRole('button', { name: 'Submit' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Finish warm-up' }))
}

describe('Session (daily loop, issue #19)', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
    // jsdom's `window.history` is shared across every test in this file, so an entry
    // a prior test pushed while entering a content page would otherwise still be
    // there for this test's own `history.back()` to land on - reset to a clean base,
    // matching a real fresh page load, before each test.
    window.history.replaceState(null, '', '/')
  })
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('opens straight on the first rep, with no gate in front of it', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)

    // The first rep is on screen without having to click through anything.
    expect(await screen.findByRole('heading', { name: 'Rep One' })).toBeInTheDocument()
    expect(screen.getByText('Which pattern fits?')).toBeInTheDocument()
  })

  it('completing the warm-up marks the day complete, with no further action', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetch(calls))
    render(<Session />)

    await finishWarmup()

    expect(await screen.findByRole('heading', { name: 'Day complete' })).toBeInTheDocument()
    // The completion was recorded, and the streak shows - the whole obligation is met.
    expect(calls.completeWarmup).toBe(1)
    // The streak shows (both in the landing copy and the header badge).
    expect(screen.getAllByText(/3-day streak/).length).toBeGreaterThan(0)
  })

  it('declining the main exercise still leaves the day complete', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)
    await finishWarmup()
    await screen.findByRole('heading', { name: 'Day complete' })

    fireEvent.click(screen.getByRole('button', { name: 'I am done for today' }))

    // Declining is a full success, not an unfinished day.
    expect(await screen.findByRole('heading', { name: 'Done for today' })).toBeInTheDocument()
    expect(screen.getByText(/See you tomorrow/)).toBeInTheDocument()
  })

  it('offers a main exercise that is a CHALLENGE of any domain, then uncapped continuation', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)
    await finishWarmup()
    await screen.findByRole('heading', { name: 'Day complete' })

    fireEvent.click(screen.getByRole('button', { name: 'Start a main exercise' }))

    // The main defaults to a CHALLENGE (the concept one is first in the catalog), proving
    // the flow never assumes an algorithm.
    expect(await screen.findByRole('heading', { name: 'Concept Main' })).toBeInTheDocument()

    // Continuation is uncapped: another exercise can always be picked next.
    await selectExercise('Code Main')
    expect(await screen.findByRole('heading', { name: 'Code Main' })).toBeInTheDocument()
  })

  it('reaches the practice surface directly via the Practice tab, without the warm-up', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))

    expect(await screen.findByRole('heading', { name: 'Concept Main' })).toBeInTheDocument()
  })

  it('does not complete the day just by opening the practice surface', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetch(calls))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
    await screen.findByRole('heading', { name: 'Concept Main' })

    // Only finishing the warm-up completes the day - browsing exercises never does.
    await waitFor(() => expect(calls.completeWarmup).toBe(0))
  })

  it('with a non-empty warm-up, solving a main exercise does not complete the day', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetch(calls))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    // Skip the warm-up and solve a main directly - the main is optional and must not carry
    // the weight of completing the day when the warm-up is still there to be run.
    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
    await screen.findByRole('heading', { name: 'Concept Main' })
    fireEvent.click(screen.getByLabelText('Speeds reads'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
    await waitFor(() => expect(calls.completeWarmup).toBe(0))
  })

  it('completes the day off a single Practice exercise when the warm-up set is empty', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetch(calls, { warmup: [] }))
    render(<Session />)

    // An empty warm-up shows the fallback plainly and completes nothing on its own.
    expect(await screen.findByRole('heading', { name: /nothing to warm up/i })).toBeInTheDocument()
    expect(
      screen.getByText(/completing any one exercise in Practice finishes your day/i),
    ).toBeInTheDocument()
    await waitFor(() => expect(calls.completeWarmup).toBe(0))

    // Solving one Practice exercise is now the path that banks the day.
    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
    await screen.findByRole('heading', { name: 'Concept Main' })
    fireEvent.click(screen.getByLabelText('Speeds reads'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    await waitFor(() => expect(calls.completeWarmup).toBe(1))
  })

  it('reaches the readiness picture via its own tab, as a primary surface not tucked behind Practice', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Readiness' }))

    expect(await screen.findByRole('heading', { name: 'Readiness' })).toBeInTheDocument()
    expect(await screen.findByText('1/3')).toBeInTheDocument()
  })

  it('links straight to readiness from the day-complete landing', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }))
    render(<Session />)
    await finishWarmup()
    await screen.findByRole('heading', { name: 'Day complete' })

    fireEvent.click(screen.getByRole('button', { name: 'See your readiness' }))

    expect(await screen.findByRole('heading', { name: 'Readiness' })).toBeInTheDocument()
  })

  it('reaches the Learn surface via the Learn tab, without touching the warm-up', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    const base = mockFetch(calls)
    // Layer lesson endpoints on top of the shared session mock for this test only.
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/lessons'))
          return {
            ok: true,
            json: async () => [
              { id: 'l1', title: 'A Lesson', domain: 'fundamentals', difficulty: 'EASY', promptCount: 1 },
            ],
          } as Response
        if (href.endsWith('/api/lessons/l1/read'))
          // Reading a lesson seeds its checks into the warm-up (issue #40): a best-effort POST
          // fired when the lesson opens.
          return { ok: true, json: async () => ({}) } as Response
        if (href.endsWith('/api/lessons/l1'))
          return {
            ok: true,
            json: async () => ({
              id: 'l1',
              title: 'A Lesson',
              statement: 'Taught content.',
              domain: 'fundamentals',
              difficulty: 'EASY',
              prompts: [{ prompt: 'Explain it.', modelAnswer: 'Because.' }],
            }),
          } as Response
        return base(url, init)
      }),
    )
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))

    expect(await screen.findByRole('heading', { name: 'A Lesson' })).toBeInTheDocument()
    expect(screen.getByText('Explain it.')).toBeInTheDocument()
    // Opening Learn never completes the day.
    await waitFor(() => expect(calls.completeWarmup).toBe(0))
  })

  // Stubs the Learn endpoints (a single lesson, no prompts) on top of the shared
  // session mock - used by the two history/hash tests below, which both need Learn
  // mounted alongside Practice to prove Practice's retired breadcrumb changed nothing
  // about Learn's own contentNav.ts mechanism (AGENTS.md's "Dedicated content pages").
  function mockFetchWithLesson(calls: Calls) {
    const base = mockFetch(calls)
    return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      if (href.endsWith('/api/lessons'))
        return {
          ok: true,
          json: async () => [
            { id: 'l1', title: 'A Lesson', domain: 'fundamentals', difficulty: 'EASY', promptCount: 0 },
          ],
        } as Response
      if (href.endsWith('/api/lessons/l1/read')) return { ok: true, json: async () => ({}) } as Response
      if (href.endsWith('/api/lessons/l1'))
        return {
          ok: true,
          json: async () => ({
            id: 'l1',
            title: 'A Lesson',
            statement: 'Taught content.',
            domain: 'fundamentals',
            difficulty: 'EASY',
            prompts: [],
          }),
        } as Response
      return base(url, init)
    })
  }

  // Practice's breadcrumb (and the browser-history push/pop it drove) is retired
  // (captain-approved full-screen redesign, issue: swe-practice-fs-build): it never
  // touches `window.history` at all now, and its full-screen shell has no tab bar of
  // its own - the brand icon (aria-label "Back to Today") is the only way out, so a
  // direct Practice<->Learn tab switch (the old test's premise) is no longer a reachable
  // UI path at all. What actually needs proving now is narrower: Practice contributes
  // zero history churn, however it is entered, exited, or navigated within.
  it('Practice never touches browser history now its breadcrumb is retired', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetch(calls))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    // Guarantee a clean top-of-stack (a push truncates any forward entries a prior
    // test in this shared jsdom window may have left) so the depth delta below is
    // measured against a known baseline, not stale history.
    window.history.pushState(null, '', '/')
    const before = window.history.length

    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
    await screen.findByRole('heading', { name: 'Concept Main' })
    expect(window.history.length).toBe(before)

    // Picking a different exercise through the "Problem List" sidebar doesn't touch
    // history either - selection is a plain state change, not a navigation.
    await selectExercise('Code Main')
    expect(await screen.findByRole('heading', { name: 'Code Main' })).toBeInTheDocument()
    expect(window.history.length).toBe(before)

    fireEvent.click(screen.getByRole('button', { name: 'Back to Today' }))
    await screen.findByRole('heading', { name: 'Rep One' })
    expect(window.history.length).toBe(before)
  })

  // A trip through Today or Readiness deliberately resets "is a content page open"
  // (clearContentHashIfOpen, unchanged by this redesign) - so re-entering Learn after
  // one is a fresh push, not a leak. The old "Practice<->Learn switch replaces in
  // place" scenario `enterAutoPickedContent` was built for is no longer reachable at
  // all in this UI (Practice's full-screen shell has no Learn button; every path
  // between the two now transits Today, which always clears first) - this documents
  // the real, current behavior rather than asserting a now-unreachable invariant.
  it('a trip through Today clears any open Learn content hash, so re-entering Learn pushes a fresh entry', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetchWithLesson(calls))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    window.history.pushState(null, '', '/')
    const before = window.history.length

    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))
    await screen.findByRole('heading', { name: 'A Lesson' })
    expect(window.history.length).toBe(before + 1)

    fireEvent.click(screen.getByRole('button', { name: 'Today' }))
    await screen.findByRole('heading', { name: 'Rep One' })
    // Cleared in place - no depth change - rather than left stranded pointing at a
    // page nothing renders anymore (the captain-reported bug the mechanism fixes).
    expect(window.location.hash).toBe('')

    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))
    await screen.findByRole('heading', { name: 'A Lesson' })
    // With nothing left to replace, this is a genuine second push.
    expect(window.history.length).toBe(before + 2)
  })

  // Regression test for the captain-reported bug: opening a content page then switching
  // to Today or Readiness left the URL hash pointing at a page nothing renders anymore,
  // only correcting itself on a later exercise/lesson navigation or a return to browse.
  // Practice is no longer part of this mechanism at all (its breadcrumb is retired, so
  // it never sets a hash the tab switch would need to clear) - Learn is where the real
  // regression coverage now lives.
  it('never sets a hash for Practice, and still clears a Learn content-page hash when switching to Today or Readiness', async () => {
    const calls = { completeWarmup: 0, abandons: [] as string[] }
    vi.stubGlobal('fetch', mockFetchWithLesson(calls))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
    await screen.findByRole('heading', { name: 'Concept Main' })
    expect(window.location.hash).toBe('')
    fireEvent.click(screen.getByRole('button', { name: 'Back to Today' }))
    await screen.findByRole('heading', { name: 'Rep One' })

    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))
    await screen.findByRole('heading', { name: 'A Lesson' })
    expect(window.location.hash).toBe('#/learn/l1')

    fireEvent.click(screen.getByRole('button', { name: 'Today' }))

    // The Today tab renders, and - the actual bug - the hash no longer claims a
    // content page nothing on screen corresponds to.
    expect(await screen.findByRole('heading', { name: 'Rep One' })).toBeInTheDocument()
    expect(window.location.hash).toBe('')

    // Same story for Readiness.
    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))
    await screen.findByRole('heading', { name: 'A Lesson' })
    expect(window.location.hash).toBe('#/learn/l1')

    fireEvent.click(screen.getByRole('button', { name: 'Readiness' }))

    expect(await screen.findByRole('heading', { name: 'Readiness' })).toBeInTheDocument()
    expect(window.location.hash).toBe('')

    // Switching back to Learn still works: the mount-time auto-pick re-enters a
    // content page normally, proving the fix does not strand Learn in a broken state.
    fireEvent.click(screen.getByRole('button', { name: 'Learn' }))
    expect(await screen.findByRole('heading', { name: 'A Lesson' })).toBeInTheDocument()
    expect(window.location.hash).toBe('#/learn/l1')
  })

  it('shows the repair nudge in the header badge when a repair is available (issue #22)', async () => {
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }, { repairPending: true }))
    render(<Session />)
    await screen.findByRole('heading', { name: 'Rep One' })

    expect(await screen.findByText(/repair it/i)).toBeInTheDocument()
    expect(screen.getByText(/2 left this month/i)).toBeInTheDocument()
  })

  it('keeps the landing and the header badge consistent when saving the completion fails', async () => {
    // The warm-up really finished, but the POST that records it fails (backend down).
    vi.stubGlobal('fetch', mockFetch({ completeWarmup: 0, abandons: [] }, { failComplete: true }))
    const { container } = render(<Session />)
    await finishWarmup()

    // The landing shows the day complete - the practice really happened...
    expect(await screen.findByRole('heading', { name: 'Day complete' })).toBeInTheDocument()
    // ...and the header badge agrees rather than contradicting it, even though the save failed,
    // so the two can never disagree.
    await waitFor(() =>
      expect(container.querySelector('.day-badge.complete')).toBeInTheDocument(),
    )
  })
})
