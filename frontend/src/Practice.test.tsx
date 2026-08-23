import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Practice from './Practice'

// Monaco needs a canvas jsdom cannot give it; a self-check item never renders it, but the
// import is stubbed so nothing pulls the real editor in.
vi.mock('@monaco-editor/react', () => ({
  default: ({ defaultValue }: { defaultValue?: string }) => (
    <textarea aria-label="editor" defaultValue={defaultValue} />
  ),
}))

const CATALOG = [
  {
    id: 'explain-gd',
    title: 'Explain gradient descent',
    domain: 'ai-ml',
    difficulty: 'MEDIUM',
    form: 'CHALLENGE',
  },
]

const EXPLAIN = {
  id: 'explain-gd',
  title: 'Explain gradient descent',
  statement: 'Explain, in your own words, what gradient descent does.',
  domain: 'ai-ml',
  difficulty: 'MEDIUM',
  response: { kind: 'selfCheck' },
  hints: [],
  hasExplanation: false,
}

const MODEL_ANSWER = 'It steps parameters down the negative gradient of the loss.'

type Calls = { reveal: number; rating: string | null; revealedProduced: string | null }

function mockFetch(calls: Calls) {
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url)
    const method = init?.method ?? 'GET'
    if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CATALOG } as Response
    if (href.includes('/api/exercises/explain-gd'))
      return { ok: true, json: async () => EXPLAIN } as Response
    if (href.endsWith('/api/attempts') && method === 'POST')
      return { ok: true, json: async () => ({ id: 'att-1' }) } as Response
    if (href.endsWith('/api/attempts')) return { ok: true, json: async () => [] } as Response
    if (href.endsWith('/self-check/reveal')) {
      calls.reveal += 1
      calls.revealedProduced = JSON.parse(String(init?.body)).produced
      return {
        ok: true,
        json: async () => ({ submissionId: 'sub-1', modelAnswer: MODEL_ANSWER }),
      } as Response
    }
    if (href.endsWith('/self-check/rating')) {
      calls.rating = JSON.parse(String(init?.body)).rating
      return {
        ok: true,
        json: async () => ({
          rating: calls.rating,
          attempt: { id: 'att-1', outcome: 'EXPLAINED' },
        }),
      } as Response
    }
    throw new Error(`unexpected fetch to ${method} ${href}`)
  })
}

describe('Practice self-check (issue #41)', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('produces, then reveals the model answer only afterwards, then records a self-rating', async () => {
    const calls: Calls = { reveal: 0, rating: null, revealedProduced: null }
    vi.stubGlobal('fetch', mockFetch(calls))
    render(<Practice />)

    // The explain item is the default main.
    expect(await screen.findByRole('heading', { name: 'Explain gradient descent' })).toBeInTheDocument()

    // The model answer is not on the page before the learner commits their own text.
    expect(screen.queryByText(MODEL_ANSWER)).not.toBeInTheDocument()

    // Reveal is disabled until something is produced.
    const revealButton = screen.getByRole('button', { name: 'Reveal the model answer' })
    expect(revealButton).toBeDisabled()

    fireEvent.change(screen.getByLabelText('Explain it in your own words'), {
      target: { value: 'my cold explanation' },
    })
    expect(revealButton).toBeEnabled()
    fireEvent.click(revealButton)

    // The model answer appears only after the reveal, and the produced text was committed.
    expect(await screen.findByText(MODEL_ANSWER)).toBeInTheDocument()
    expect(calls.reveal).toBe(1)
    expect(calls.revealedProduced).toBe('my cold explanation')

    // Self-rating is recorded, and the flow confirms it without changing any score.
    fireEvent.click(screen.getByRole('button', { name: 'Nailed it' }))
    await waitFor(() => expect(calls.rating).toBe('NAILED_IT'))
    expect(await screen.findByText(/Explanation recorded/)).toBeInTheDocument()
  })

  it('never machine-grades the free text: there is no Run/Submit button', async () => {
    vi.stubGlobal('fetch', mockFetch({ reveal: 0, rating: null, revealedProduced: null }))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Explain gradient descent' })

    // A self-check has no graded submit path at all - only produce-then-reveal.
    expect(screen.queryByRole('button', { name: 'Run' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit' })).not.toBeInTheDocument()
  })
})

// The complexity self-report flow (issue #17): a code exercise with a complexity
// check prompts for a claim only once solved, and the authored target never appears
// on the page before that claim is submitted.
describe('Practice complexity self-report (issue #17)', () => {
  const CODE_CATALOG = [
    { id: 'sum-demo', title: 'Sum Demo', domain: 'algorithms', difficulty: 'EASY', form: 'CHALLENGE' },
  ]
  const CODE_EXERCISE = {
    id: 'sum-demo',
    title: 'Sum Demo',
    statement: 'Sum the array.',
    domain: 'algorithms',
    difficulty: 'EASY',
    response: { kind: 'code', language: 'java', stub: 'class Solution {}' },
    hints: [],
    hasExplanation: false,
    hasComplexityCheck: true,
  }

  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  function mockCodeFetch(
    claimedBody: { time: string; space: string }[],
    options: {
      solutionCommitted?: boolean
      modelOpinionAvailable?: boolean
      modelOpinion?: unknown
    } = {},
  ) {
    return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      const method = init?.method ?? 'GET'
      if (href.endsWith('/api/exercises')) return { ok: true, json: async () => CODE_CATALOG } as Response
      if (href.includes('/api/exercises/sum-demo'))
        return { ok: true, json: async () => CODE_EXERCISE } as Response
      if (href.endsWith('/api/attempts') && method === 'POST')
        return { ok: true, json: async () => ({ id: 'att-1' }) } as Response
      if (href.endsWith('/api/attempts')) return { ok: true, json: async () => [] } as Response
      if (href.endsWith('/submissions'))
        return {
          ok: true,
          json: async () => ({
            outcome: 'PASSED',
            passed: 1,
            total: 1,
            detail: '',
            runtimeMillis: 5,
            solutionCommitted: options.solutionCommitted ?? false,
          }),
        } as Response
      if (href.endsWith('/complexity/model-opinion')) {
        return { ok: true, json: async () => options.modelOpinion } as Response
      }
      if (href.endsWith('/complexity')) {
        claimedBody.push(JSON.parse(String(init?.body)))
        return {
          ok: true,
          json: async () => ({
            targetTime: 'LINEAR',
            targetSpace: 'CONSTANT',
            status: 'CONSISTENT',
            modelOpinionAvailable: options.modelOpinionAvailable ?? false,
          }),
        } as Response
      }
      throw new Error(`unexpected fetch to ${method} ${href}`)
    })
  }

  it('prompts for a claim only after solving, and never shows the target before it is submitted', async () => {
    const claims: { time: string; space: string }[] = []
    vi.stubGlobal('fetch', mockCodeFetch(claims))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Sum Demo' })

    // Not solved yet: no complexity prompt, and the target vocabulary is nowhere on
    // the page - it cannot leak before a claim exists to gate it.
    expect(screen.queryByText(/complexity/i)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    expect(await screen.findByText('1 of 1 tests passed')).toBeInTheDocument()

    // Solved: the claim prompt appears, still with no target revealed.
    expect(await screen.findByText("What is your solution's complexity?")).toBeInTheDocument()
    expect(screen.queryByText(/Authored target/)).not.toBeInTheDocument()
    expect(claims).toHaveLength(0)

    fireEvent.click(screen.getByRole('button', { name: 'Submit complexity claim' }))

    // Only now - after the claim was sent - does the target appear, worded as
    // "consistent with", never flatly "correct" (issue #17's honesty constraint).
    expect(await screen.findByText(/Authored target/)).toBeInTheDocument()
    expect(screen.getByText('Measured scaling is consistent with your claim.')).toBeInTheDocument()
    expect(claims).toEqual([{ time: 'LINEAR', space: 'LINEAR' }])
  })

  it('shows an honest note when the solution was auto-committed (issue #22)', async () => {
    vi.stubGlobal('fetch', mockCodeFetch([], { solutionCommitted: true }))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Sum Demo' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText(/committed to your solutions repo/i)).toBeInTheDocument()
  })

  it('shows no commit note when the solution was not committed', async () => {
    vi.stubGlobal('fetch', mockCodeFetch([], { solutionCommitted: false }))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Sum Demo' })

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    await screen.findByText('1 of 1 tests passed')
    expect(screen.queryByText(/committed to your solutions repo/i)).not.toBeInTheDocument()
  })

  // The LLM complexity second opinion (issue #83): a third, advisory voice offered
  // only when the backend says a model advisor is configured - key-absent means the
  // action is simply absent, never a broken button.
  describe('model second opinion (issue #83)', () => {
    async function solveAndClaim() {
      fireEvent.click(screen.getByRole('button', { name: 'Run' }))
      await screen.findByText('1 of 1 tests passed')
      fireEvent.click(await screen.findByRole('button', { name: 'Submit complexity claim' }))
      await screen.findByText(/Authored target/)
    }

    it('offers no second opinion action when no advisor is configured', async () => {
      vi.stubGlobal('fetch', mockCodeFetch([], { modelOpinionAvailable: false }))
      render(<Practice />)
      await screen.findByRole('heading', { name: 'Sum Demo' })
      await solveAndClaim()

      expect(screen.queryByRole('button', { name: 'Get a second opinion' })).not.toBeInTheDocument()
    })

    it('renders quiet confirmation when the model agrees', async () => {
      vi.stubGlobal(
        'fetch',
        mockCodeFetch([], {
          modelOpinionAvailable: true,
          modelOpinion: { modelTime: 'LINEAR', modelReasoning: 'Single pass.', agreement: true },
        }),
      )
      render(<Practice />)
      await screen.findByRole('heading', { name: 'Sum Demo' })
      await solveAndClaim()

      fireEvent.click(screen.getByRole('button', { name: 'Get a second opinion' }))

      expect(await screen.findByText(/The model agrees/)).toBeInTheDocument()
      // Agreement renders quietly - the reasoning behind an agreeing reading is not
      // surfaced, since there is nothing here for the learner to resolve.
      expect(screen.queryByText('Single pass.')).not.toBeInTheDocument()
    })

    it('renders the disagreement as a neutral prompt, never a verdict', async () => {
      const prompt = 'You claimed O(n), measurement found O(n), and the model reads this as O(n²) - which is right, and why?'
      vi.stubGlobal(
        'fetch',
        mockCodeFetch([], {
          modelOpinionAvailable: true,
          modelOpinion: {
            modelTime: 'QUADRATIC',
            modelReasoning: 'Looks like nested iteration over the array.',
            agreement: false,
            disagreementPrompt: prompt,
          },
        }),
      )
      render(<Practice />)
      await screen.findByRole('heading', { name: 'Sum Demo' })
      await solveAndClaim()

      fireEvent.click(screen.getByRole('button', { name: 'Get a second opinion' }))

      expect(await screen.findByText(prompt)).toBeInTheDocument()
      expect(screen.getByText('Looks like nested iteration over the array.')).toBeInTheDocument()
      expect(screen.queryByText(/The model agrees/)).not.toBeInTheDocument()
    })
  })
})

// Language selection (issue #26): Java is the default, a second language (Python) is
// offered from the backend's own list, and switching regenerates the stub for the
// exact same exercise - proving the picker drives the language-neutral adapter seam
// rather than a second, parallel exercise.
describe('Practice language selection (issue #26)', () => {
  const LANG_CATALOG = [
    { id: 'pair-demo', title: 'Pair Demo', domain: 'algorithms', difficulty: 'EASY', form: 'CHALLENGE' },
  ]
  const JAVA_EXERCISE = {
    id: 'pair-demo',
    title: 'Pair Demo',
    statement: 'Return the two arguments.',
    domain: 'algorithms',
    difficulty: 'EASY',
    response: { kind: 'code', language: 'java', stub: 'class Solution {}' },
    hints: [],
    hasExplanation: false,
  }
  const PYTHON_EXERCISE = {
    ...JAVA_EXERCISE,
    response: { kind: 'code', language: 'python', stub: 'class Solution:\n    pass\n' },
  }

  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  function mockLanguageFetch(submittedBodies: Record<string, unknown>[]) {
    return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      const method = init?.method ?? 'GET'
      if (href.endsWith('/api/exercises')) return { ok: true, json: async () => LANG_CATALOG } as Response
      if (href.endsWith('/api/languages'))
        return { ok: true, json: async () => ['java', 'python'] } as Response
      if (href.includes('/api/exercises/pair-demo')) {
        const requested = new URL(href, 'http://localhost').searchParams.get('language')
        return {
          ok: true,
          json: async () => (requested === 'python' ? PYTHON_EXERCISE : JAVA_EXERCISE),
        } as Response
      }
      if (href.endsWith('/api/attempts') && method === 'POST')
        return { ok: true, json: async () => ({ id: 'att-1' }) } as Response
      if (href.endsWith('/api/attempts')) return { ok: true, json: async () => [] } as Response
      if (href.endsWith('/submissions')) {
        submittedBodies.push(JSON.parse(String(init?.body)))
        return {
          ok: true,
          json: async () => ({ outcome: 'PASSED', passed: 1, total: 1, detail: '' }),
        } as Response
      }
      throw new Error(`unexpected fetch to ${method} ${href}`)
    })
  }

  it('defaults to Java, and switching to Python regenerates the stub for the same exercise', async () => {
    const submitted: Record<string, unknown>[] = []
    vi.stubGlobal('fetch', mockLanguageFetch(submitted))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Pair Demo' })

    // Java is the default, with no language chosen up front.
    expect(screen.getByLabelText('Language')).toHaveValue('java')
    expect(screen.getByLabelText('editor')).toHaveValue('class Solution {}')

    fireEvent.change(screen.getByLabelText('Language'), { target: { value: 'python' } })

    // Same exercise, same heading - only the stub (now Python) changed.
    expect(await screen.findByRole('heading', { name: 'Pair Demo' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('editor')).toHaveValue('class Solution:\n    pass\n'))

    fireEvent.click(screen.getByRole('button', { name: 'Run' }))
    await waitFor(() => expect(submitted).toHaveLength(1))
    expect(submitted[0].language).toBe('python')
  })

  it('offers every language the backend reports, Java first', async () => {
    vi.stubGlobal('fetch', mockLanguageFetch([]))
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Pair Demo' })

    const options = screen
      .getAllByRole('option', { name: /^(java|python)$/ })
      .map((option) => (option as HTMLOptionElement).value)
    expect(options).toEqual(['java', 'python'])
  })
})

// The SQL query response (issue #25): it renders through the exact same editor and
// attempt-submission flow as a code exercise, proving the session loop needed no
// SQL-specific path, but its verdict is worded in rows rather than tests - the minimal
// failure signal decision issue #10 settled on.
describe('Practice SQL query response (issue #25)', () => {
  const SQL_CATALOG = [
    { id: 'top-customers', title: 'Top Customers', domain: 'sql', difficulty: 'EASY', form: 'CHALLENGE' },
  ]
  const SQL_EXERCISE = {
    id: 'top-customers',
    title: 'Top Customers',
    statement: 'Return every customer id and name.',
    domain: 'sql',
    difficulty: 'EASY',
    response: { kind: 'query', language: 'sql', stub: '' },
    hints: [],
    hasExplanation: false,
  }

  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  function mockSqlFetch(verdict: Record<string, unknown>) {
    return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      const method = init?.method ?? 'GET'
      if (href.endsWith('/api/exercises')) return { ok: true, json: async () => SQL_CATALOG } as Response
      if (href.includes('/api/exercises/top-customers'))
        return { ok: true, json: async () => SQL_EXERCISE } as Response
      if (href.endsWith('/api/attempts') && method === 'POST')
        return { ok: true, json: async () => ({ id: 'att-1' }) } as Response
      if (href.endsWith('/api/attempts')) return { ok: true, json: async () => [] } as Response
      if (href.endsWith('/submissions')) return { ok: true, json: async () => verdict } as Response
      throw new Error(`unexpected fetch to ${method} ${href}`)
    })
  }

  it('renders the same Monaco editor and Run flow as a code exercise, worded in rows', async () => {
    vi.stubGlobal(
      'fetch',
      mockSqlFetch({ outcome: 'FAILED', passed: 3, total: 5, detail: '', runtimeMillis: 4 }),
    )
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Top Customers' })

    // Same editor, same action button label as a code exercise - no parallel surface.
    expect(screen.getByLabelText('editor')).toBeInTheDocument()
    const runButton = screen.getByRole('button', { name: 'Run' })

    fireEvent.click(runButton)

    // The minimal failure signal is a bare row count, never "N of M matched" (issue #25).
    expect(await screen.findByText('Returned 3 rows, expected 5')).toBeInTheDocument()
  })

  it('reports a refused write as a query error, not a compile error', async () => {
    vi.stubGlobal(
      'fetch',
      mockSqlFetch({
        outcome: 'COMPILE_ERROR',
        passed: 0,
        total: 0,
        detail: 'ERROR: cannot execute DROP TABLE in a read-only transaction',
        runtimeMillis: 0,
      }),
    )
    render(<Practice />)
    await screen.findByRole('heading', { name: 'Top Customers' })
    fireEvent.click(screen.getByRole('button', { name: 'Run' }))

    expect(await screen.findByText('Query error')).toBeInTheDocument()
    expect(
      screen.getByText('ERROR: cannot execute DROP TABLE in a read-only transaction'),
    ).toBeInTheDocument()
  })
})
