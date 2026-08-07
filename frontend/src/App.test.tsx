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
// the run endpoint for whichever exercise is posted to.
function mockFetch(run: unknown, runOk = true) {
  return vi.fn(async (url: string | URL | Request) => {
    const href = String(url)
    if (href.endsWith('/api/exercises')) {
      return { ok: true, json: async () => CATALOG } as Response
    }
    if (href.endsWith('/api/exercises/two-sum')) {
      return { ok: true, json: async () => CODE_EXERCISE } as Response
    }
    if (href.endsWith('/api/exercises/hashmap-lookup')) {
      return { ok: true, json: async () => CHOICE_EXERCISE } as Response
    }
    if (href.endsWith('/run')) {
      return { ok: runOk, status: runOk ? 200 : 500, json: async () => run } as Response
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

    expect(await screen.findByRole('heading', { name: 'Two Sum' })).toBeInTheDocument()
    expect(screen.getByText(/two numbers that add up/i)).toBeInTheDocument()
    expect(screen.getByLabelText('editor')).toHaveValue('class Solution {}')
  })

  it('reports the passing-test count after running code', async () => {
    vi.stubGlobal('fetch', mockFetch({ outcome: 'FAILED', passed: 3, total: 4, detail: '' }))

    render(<App />)
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
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText(/compile error/i)).toBeInTheDocument()
    expect(screen.getByText(/cannot find symbol/i)).toBeInTheDocument()
  })

  it('lets the user select a concept exercise and answer it as a choice', async () => {
    vi.stubGlobal('fetch', mockFetch({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }))

    render(<App />)
    await screen.findByRole('heading', { name: 'Two Sum' })

    fireEvent.change(screen.getByLabelText('Exercise'), { target: { value: 'hashmap-lookup' } })

    expect(await screen.findByRole('heading', { name: 'Hash Map Lookup' })).toBeInTheDocument()
    // A choice exercise shows options, not the code editor.
    expect(screen.queryByLabelText('editor')).not.toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('O(1)'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
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
})
