import { cleanup, fireEvent, render, screen } from '@testing-library/react'
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
  await screen.findByLabelText('Exercise')
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
    if (href.endsWith('/api/exercises/two-sum')) {
      return { ok: true, json: async () => CODE_EXERCISE } as Response
    }
    if (href.endsWith('/api/exercises/hashmap-lookup')) {
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

    fireEvent.change(screen.getByLabelText('Exercise'), { target: { value: 'hashmap-lookup' } })

    expect(await screen.findByRole('heading', { name: 'Hash Map Lookup' })).toBeInTheDocument()
    // A choice exercise shows options, not the code editor.
    expect(screen.queryByLabelText('editor')).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('O(1)'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
  })

  it('shows past attempts in the history list', async () => {
    const attempts = [
      {
        id: 'a1',
        exerciseId: 'two-sum',
        exerciseTitle: 'Two Sum',
        domain: 'algorithms',
        form: 'CHALLENGE',
        outcome: 'ABANDONED',
        startedAt: '2026-08-06T10:00:00Z',
        endedAt: '2026-08-06T10:05:00Z',
        submissionCount: 2,
        hintsTaken: 0,
        failingCaseRevealed: true,
      },
    ]
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.endsWith('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/attempts')) return { ok: true, json: async () => attempts } as Response
        throw new Error(`unexpected fetch to ${href}`)
      }) as unknown as typeof fetch,
    )

    render(<App />)
    await gotoPractice()

    expect(await screen.findByRole('heading', { name: 'History' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: 'abandoned' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '2' })).toBeInTheDocument()
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
        if (href.endsWith('/api/exercises/two-sum'))
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
        if (href.endsWith('/api/exercises/two-sum'))
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

  it('shows the check explanation automatically on a wrong answer', async () => {
    const explainingCheck = { ...CHOICE_EXERCISE, hasExplanation: true }
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
        const href = String(url)
        if (href.endsWith('/api/reps/warmup')) return { ok: true, json: async () => [] } as Response
        if (href.endsWith('/api/session')) return { ok: true, json: async () => ({ dayComplete: false, completedAt: null, streak: 0 }) } as Response
        if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
        if (href.endsWith('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/exercises/hashmap-lookup'))
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
    fireEvent.change(screen.getByLabelText('Exercise'), { target: { value: 'hashmap-lookup' } })
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
        if (href.endsWith('/api/exercises/two-sum'))
          return { ok: true, json: async () => CODE_EXERCISE } as Response
        if (href.endsWith('/api/exercises/hashmap-lookup'))
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
    fireEvent.change(screen.getByLabelText('Exercise'), { target: { value: 'hashmap-lookup' } })
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
