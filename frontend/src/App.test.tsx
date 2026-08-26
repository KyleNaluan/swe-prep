import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

// Monaco needs a real DOM/canvas it cannot get in jsdom, so stand it in with a
// textarea that speaks the same onChange contract the app relies on.
vi.mock('@monaco-editor/react', () => ({
  default: ({
    defaultValue,
    onChange,
  }: {
    defaultValue?: string
    onChange?: (value: string | undefined) => void
  }) => (
    <textarea
      aria-label="editor"
      defaultValue={defaultValue}
      onChange={(event) => onChange?.(event.target.value)}
    />
  ),
}))

const CATALOG = [
  { id: 'two-sum', title: 'Two Sum', domain: 'algorithms', difficulty: 'EASY', form: 'CHALLENGE' },
  {
    id: 'hashmap-lookup',
    title: 'Hash Map Lookup',
    domain: 'fundamentals',
    difficulty: 'EASY',
    form: 'REP',
  },
]

const CODE_EXERCISE = {
  id: 'two-sum',
  title: 'Two Sum',
  statement: 'Return the indices of the two numbers that add up to target.',
  domain: 'algorithms',
  difficulty: 'EASY',
  form: 'CHALLENGE',
  response: { kind: 'code', language: 'java', stub: 'class Solution {}' },
}

const CHOICE_EXERCISE = {
  id: 'hashmap-lookup',
  title: 'Hash Map Lookup',
  statement: 'What is the average-case lookup cost?',
  domain: 'fundamentals',
  difficulty: 'EASY',
  form: 'REP',
  response: { kind: 'choice', options: ['O(1)', 'O(log n)', 'O(n)'] },
}

// Routes the app's fetches to canned responses. `run` is the verdict returned by
// posting a submission for whichever attempt is posted to. Starting an attempt and
// listing history are stubbed so the lazy-start + history flow (issue #15) resolves.
// The app opens on the warm-up (issue #18); these editor tests are about the Practice
// surface, so they answer the warm-up fetch with an empty set and switch to the Practice
// tab first.
async function gotoPractice() {
  fireEvent.click(screen.getByRole('button', { name: 'Practice' }))
  await screen.findByRole('button', { name: 'Problem List' })
}

// Selects an exercise through the TreeBrowser inside the "Problem List" overlay
// sidebar (captain-approved full-screen redesign, issue: swe-practice-fs-build -
// Practice's old inline browse tree and breadcrumb are retired): open the sidebar,
// search narrows it to a cross-domain match, then the matching row is clicked -
// closing the sidebar automatically, exactly the real user path. The two-item
// CATALOG below spans two domains, so this is the only reliable way to reach the
// second exercise without first knowing - and clicking into - the domain it lives in.
async function selectExercise(title: string) {
  fireEvent.click(screen.getByRole('button', { name: 'Problem List' }))
  const sidebar = await screen.findByRole('dialog', { name: 'Problem list' })
  fireEvent.change(within(sidebar).getByLabelText('Find a problem'), { target: { value: title } })
  fireEvent.click(within(sidebar).getByRole('button', { name: new RegExp(title) }))
}

function mockFetch(run: unknown, runOk = true) {
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url)
    if (href.endsWith('/api/reps/warmup')) {
      return { ok: true, json: async () => [] } as Response
    }
    if (href.endsWith('/api/session')) {
      return {
        ok: true,
        json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }),
      } as Response
    }
    if (href.endsWith('/api/exercises')) {
      return { ok: true, json: async () => CATALOG } as Response
    }
    if (href.includes('/api/exercises/two-sum')) {
      return { ok: true, json: async () => CODE_EXERCISE } as Response
    }
    if (href.includes('/api/exercises/hashmap-lookup')) {
      return { ok: true, json: async () => CHOICE_EXERCISE } as Response
    }
    // GET history / POST start both target /api/attempts.
    if (href.endsWith('/api/attempts')) {
      if (init?.method === 'POST') {
        return { ok: true, json: async () => ({ id: 'attempt-1', submissionCount: 0 }) } as Response
      }
      return { ok: true, json: async () => [] } as Response
    }
    if (href.endsWith('/submissions')) {
      return { ok: runOk, status: runOk ? 200 : 500, json: async () => run } as Response
    }
    if (href.endsWith('/abandon')) {
      return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
    }
    throw new Error(`unexpected fetch to ${href}`)
  })
}

describe('App', () => {
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

  it('loads the first exercise with its statement and stub', async () => {
    vi.stubGlobal('fetch', mockFetch({}))

    render(<App />)
    await gotoPractice()

    expect(await screen.findByRole('heading', { name: 'Two Sum' })).toBeInTheDocument()
    expect(screen.getByText(/two numbers that add up/i)).toBeInTheDocument()
    expect(screen.getByLabelText('editor')).toHaveValue('class Solution {}')
  })

  it('reports the passing-test count after running code', async () => {
    vi.stubGlobal('fetch', mockFetch({ outcome: 'FAILED', passed: 3, total: 4, detail: '' }))

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText('3 of 4 tests passed')).toBeInTheDocument()
  })

  it('distinguishes a compile error from a test failure', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({
        outcome: 'COMPILE_ERROR',
        passed: 0,
        total: 0,
        detail: 'Solution.java:3: cannot find symbol',
      }),
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText(/compile error/i)).toBeInTheDocument()
    expect(screen.getByText(/cannot find symbol/i)).toBeInTheDocument()
  })

  it('lets the user select a concept exercise and answer it as a choice', async () => {
    vi.stubGlobal('fetch', mockFetch({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }))

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    await selectExercise('Hash Map Lookup')

    expect(await screen.findByRole('heading', { name: 'Hash Map Lookup' })).toBeInTheDocument()
    // A choice exercise shows options, not the code editor.
    expect(screen.queryByLabelText('editor')).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('O(1)'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
  })

  // Practice's old dedicated-content-page breadcrumb is retired (captain-approved
  // full-screen redesign, issue: swe-practice-fs-build): the browse tree now lives
  // only inside the "Problem List" overlay sidebar, which is never unmounted while
  // closed - only hidden - so Direction C's position-preserving filters (the
  // captain's confirmed feature of PR #89) still hold with nothing to explicitly
  // restore: closing and reopening the sidebar finds the tree exactly as it was left.
  it('opens the Problem List as an overlay sidebar, and closing/reopening it preserves the open domain and filters exactly', async () => {
    vi.stubGlobal('fetch', mockFetch({}))

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    // The auto-picked exercise already opened straight into the workspace, sidebar
    // closed - aria-hidden while closed, so the tree is not reachable until it is
    // explicitly opened.
    // aria-hidden zeroes out the computed accessible name too, so this looks the
    // element up by id rather than role+name while closed.
    expect(document.getElementById('practice-sidebar')).toHaveAttribute('aria-hidden', 'true')

    // Open the sidebar, exclude Hard, and drill into the Fundamentals domain.
    fireEvent.click(screen.getByRole('button', { name: 'Problem List' }))
    const sidebar = await screen.findByRole('dialog', { name: 'Problem list' })
    fireEvent.click(within(sidebar).getByRole('button', { name: 'Hard' }))
    expect(within(sidebar).getByRole('button', { name: 'Hard' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    fireEvent.click(within(sidebar).getByRole('button', { name: /^Fundamentals/ }))

    // Pick the item from the newly opened domain - a real navigation happens, and the
    // sidebar closes on its own.
    fireEvent.click(within(sidebar).getByRole('button', { name: /Hash Map Lookup/ }))
    expect(await screen.findByRole('heading', { name: 'Hash Map Lookup' })).toBeInTheDocument()
    // aria-hidden zeroes out the computed accessible name too, so this looks the
    // element up by id rather than role+name while closed.
    expect(document.getElementById('practice-sidebar')).toHaveAttribute('aria-hidden', 'true')

    // Reopen the sidebar: the exclusion, the open domain and the current selection
    // are exactly as left - it was never unmounted, only hidden, so there is nothing
    // to restore, only to find.
    fireEvent.click(screen.getByRole('button', { name: 'Problem List' }))
    const reopened = await screen.findByRole('dialog', { name: 'Problem list' })
    expect(within(reopened).getByRole('button', { name: 'Hard' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
    expect(within(reopened).getByRole('button', { name: /^Fundamentals/ })).toHaveClass('open')
    expect(within(reopened).getByRole('button', { name: /Hash Map Lookup/ })).toHaveAttribute(
      'aria-current',
      'true',
    )
  })

  // The content page is kept mounted (hidden with CSS) across the browse<->content
  // toggle, never conditionally rendered - so returning to browse and re-opening the
  // SAME exercise never remounts the uncontrolled Monaco editor. If it did, the visible
  // editor would reset to the stub while codeRef still held the typed code, and Submit
  // (which reads codeRef) would send code the solver could no longer see.
  it('preserves typed code when returning to browse and re-opening the same exercise, and Submit sends it', async () => {
    const typed = 'class Solution { int answer = 42; }'
    let lastSubmission: string | null = null
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.includes('/api/exercises/hashmap-lookup'))
          return { ok: true, json: async () => CHOICE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/submissions')) {
          lastSubmission = JSON.parse(String(init?.body)).submission
          return {
            ok: true,
            json: async () => ({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }),
          } as Response
        }
        if (href.endsWith('/abandon')) return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    // Type code into the editor, then leave to browse and re-open the same exercise.
    fireEvent.change(screen.getByLabelText('editor'), { target: { value: typed } })
    await selectExercise('Two Sum')
    await screen.findByRole('heading', { name: 'Two Sum' })

    // The visible editor still shows the typed code, not the original stub.
    expect(screen.getByLabelText('editor')).toHaveValue(typed)

    // ...and Submit sends exactly that, so the editor and codeRef never disagree.
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    await screen.findByText('1 of 1 tests passed')
    expect(lastSubmission).toBe(typed)
  })

  // The attempt-history table moved to Readiness (captain-approved full-screen
  // redesign, issue: swe-practice-fs-build - see Readiness.test.tsx for its coverage
  // there). Practice still fetches the same history, though, since the sidebar tree's
  // solved count and checkmarks are derived from it (AGENTS.md's honest-gaps note:
  // relocating the table does not remove that need).
  it('keeps fetching attempt history for the sidebar tree even with no history table on Practice anymore', async () => {
    const attempts = [
      {
        id: 'a1',
        exerciseId: 'two-sum',
        exerciseTitle: 'Two Sum',
        domain: 'algorithms',
        form: 'CHALLENGE',
        outcome: 'SOLVED',
        startedAt: '2026-08-06T10:00:00Z',
        endedAt: '2026-08-06T10:05:00Z',
        submissionCount: 2,
        hintsTaken: 0,
        failingCaseRevealed: true,
        solutionSeen: false,
      },
    ]
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) return { ok: true, json: async () => attempts } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    expect(screen.queryByRole('heading', { name: 'History' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Problem List' }))
    const sidebar = await screen.findByRole('dialog', { name: 'Problem list' })
    expect(within(sidebar).getByText('1 of 2 solved')).toBeInTheDocument()
  })

  it('offers the hint ladder and shows a rung body only when taken', async () => {
    const exerciseWithHints = { ...CODE_EXERCISE, hints: ['Pattern', 'Approach'] }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => exerciseWithHints } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/hints'))
          return {
            ok: true,
            json: async () => ({
              rungsTaken: 1,
              totalRungs: 2,
              name: 'Pattern',
              body: 'This is a sliding window.',
            }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    // The rung body is not on the page until the hint is explicitly taken.
    expect(screen.queryByText(/sliding window/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Reveal a hint \(Pattern\)/ }))

    expect(await screen.findByText('This is a sliding window.')).toBeInTheDocument()
  })

  it('reveals the failing case after a one-line hypothesis, and withholds it by default', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/submissions'))
          return {
            ok: true,
            json: async () => ({ outcome: 'FAILED', passed: 1, total: 3, detail: '', runtimeMillis: 12 }),
          } as Response
        if (href.endsWith('/reveal'))
          return {
            ok: true,
            json: async () => ({ failingCase: { input: [3], expected: 9, actual: 6 } }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    // The failing verdict discloses only the count and the runtime - never the case.
    expect(await screen.findByText(/1 of 3 tests passed/)).toBeInTheDocument()
    expect(screen.getByText(/12 ms/)).toBeInTheDocument()
    expect(screen.queryByText(/Expected/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reveal the failing case' }))
    fireEvent.change(screen.getByLabelText(/what do you think is wrong/i), {
      target: { value: 'off-by-one on the last index' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Show the failing case' }))

    expect(await screen.findByText('Expected')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('6')).toBeInTheDocument()
  })

  it('reveals the reference solution before passing and notes it is solution-seen', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/solution'))
          return {
            ok: true,
            json: async () => ({ solution: 'class Solution { /* reference */ }', prePass: true }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    expect(screen.queryByText('class Solution { /* reference */ }')).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Show the reference solution' }))

    expect(await screen.findByText('class Solution { /* reference */ }')).toBeInTheDocument()
    // Never framed as a penalty - a plain, honest note about what it affects.
    expect(screen.getByText(/will not count as solved cold/i)).toBeInTheDocument()
  })

  it('presents a post-pass solution reveal as an unrestricted comparison, not a warning', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/submissions'))
          return { ok: true, json: async () => ({ outcome: 'PASSED', passed: 3, total: 3, detail: '' }) } as Response
        if (href.endsWith('/solution'))
          return {
            ok: true,
            json: async () => ({ solution: 'class Solution { /* reference */ }', prePass: false }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    await screen.findByText(/3 of 3 tests passed/)

    fireEvent.click(screen.getByRole('button', { name: 'Show the reference solution' }))

    expect(await screen.findByText('class Solution { /* reference */ }')).toBeInTheDocument()
    expect(screen.queryByText(/solved cold/i)).not.toBeInTheDocument()
    expect(screen.getByText(/where style and idiom learning happens/i)).toBeInTheDocument()
  })

  it('shows the check explanation automatically on a wrong answer', async () => {
    const explainingCheck = { ...CHOICE_EXERCISE, hasExplanation: true }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.includes('/api/exercises/hashmap-lookup'))
          return { ok: true, json: async () => explainingCheck } as Response
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/submissions'))
          return {
            ok: true,
            json: async () => ({
              outcome: 'FAILED',
              passed: 0,
              total: 1,
              detail: '',
              explanation: 'O(1) is right because a hash map keys straight to the bucket.',
            }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })
    await selectExercise('Hash Map Lookup')
    await screen.findByRole('heading', { name: 'Hash Map Lookup' })

    fireEvent.click(screen.getByLabelText('O(n)'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    // A wrong answer discloses the explanation on its own - no button needed.
    expect(await screen.findByText(/keys straight to the bucket/i)).toBeInTheDocument()
  })

  it('offers the explanation on request when correct, and withholds it until asked', async () => {
    const explainingCheck = { ...CHOICE_EXERCISE, hasExplanation: true }
    let explanationRequested = false
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.includes('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.includes('/api/exercises/hashmap-lookup'))
          return { ok: true, json: async () => explainingCheck } as Response
        if (href.endsWith('/explanation')) {
          explanationRequested = true
          return {
            ok: true,
            json: async () => ({ explanation: 'O(1): a hash map keys straight to the bucket.' }),
          } as Response
        }
        if (href.endsWith('/api/attempts')) {
          if (init?.method === 'POST')
            return { ok: true, json: async () => ({ id: 'attempt-1' }) } as Response
          return { ok: true, json: async () => [] } as Response
        }
        if (href.endsWith('/submissions'))
          return {
            ok: true,
            json: async () => ({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }),
          } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()
    await screen.findByRole('heading', { name: 'Two Sum' })
    await selectExercise('Hash Map Lookup')
    await screen.findByRole('heading', { name: 'Hash Map Lookup' })

    fireEvent.click(screen.getByLabelText('O(1)'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))
    expect(await screen.findByText('Correct')).toBeInTheDocument()

    // A correct answer withholds the explanation; it is one keystroke away.
    expect(screen.queryByText(/keys straight to the bucket/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Why is this the answer?' }))

    expect(await screen.findByText(/keys straight to the bucket/i)).toBeInTheDocument()
    expect(explanationRequested).toBe(true)
  })

  it('shows the backend error message when content cannot be loaded', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({
        ok: false,
        status: 500,
        json: async () => ({ error: 'Content directory not found: /nope' }),
      })) as unknown as typeof fetch,
    )

    render(<App />)

    expect(await screen.findByText(/Content directory not found/i)).toBeInTheDocument()
  })

  // The browser rejects fetch with a bare, undescriptive TypeError for a blocked or
  // failed request - including a CORS-blocked call, which was the original bug (issue
  // #34): the page loaded fine and every call went nowhere with nothing on screen
  // explaining why. Confirms that failure now surfaces an actionable message instead.
  it('names the cause when a request never reaches the backend', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch')
      }) as unknown as typeof fetch,
    )

    render(<App />)

    expect(await screen.findByText(/could not reach the backend/i)).toBeInTheDocument()
  })
})
