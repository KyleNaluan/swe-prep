import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Warmup from './Warmup'

// A choice rep (pattern-id, complexity, fill-blank, spot-bug all share this shape) and a
// free-text rep (predict-output), enough to prove both controls render and grade.
const CHOICE_REP = {
  id: 'rep-pattern',
  title: 'Pattern: sorted-pair sum',
  statement: 'Which pattern fits?',
  domain: 'algorithms',
  difficulty: 'EASY',
  form: 'REP',
  response: { kind: 'choice', options: ['Two pointers', 'Sliding window', 'Hash set'] },
  hints: [],
  hasExplanation: true,
}

const FREE_TEXT_REP = {
  id: 'rep-predict',
  title: 'Predict output',
  statement: 'Type the exact output.',
  domain: 'algorithms',
  difficulty: 'EASY',
  form: 'REP',
  response: { kind: 'freeText' },
  hints: [],
  hasExplanation: true,
}

// A fetch stub over a warm-up set of the given rep summaries, serving each rep's full
// view from `reps` and grading a submission with `verdictFor(submission)`. Records
// whether the explanation endpoint was called.
function mockWarmup(options: {
  set: { id: string }[]
  reps: Record<string, unknown>
  verdictFor: (submission: string, id: string) => unknown
  onExplanation?: () => void
}) {
  let currentExerciseId = ''
  return vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
    const href = String(url)
    if (href.endsWith('/api/reps/warmup')) {
      return { ok: true, json: async () => options.set } as Response
    }
    const exerciseMatch = href.match(/\/api\/exercises\/([^/]+)$/)
    if (exerciseMatch) {
      currentExerciseId = exerciseMatch[1]
      return { ok: true, json: async () => options.reps[exerciseMatch[1]] } as Response
    }
    if (href.endsWith('/api/attempts') && init?.method === 'POST') {
      return { ok: true, json: async () => ({ id: `attempt-${currentExerciseId}` }) } as Response
    }
    if (href.endsWith('/submissions')) {
      const body = JSON.parse(String(init?.body ?? '{}')) as { submission: string }
      return { ok: true, json: async () => options.verdictFor(body.submission, currentExerciseId) } as Response
    }
    if (href.endsWith('/explanation')) {
      options.onExplanation?.()
      return { ok: true, json: async () => ({ explanation: 'Because two pointers are O(1) space.' }) } as Response
    }
    if (href.endsWith('/abandon')) {
      return { ok: true, json: async () => ({}) } as Response
    }
    throw new Error(`unexpected fetch to ${href}`)
  })
}

describe('Warmup', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('renders a choice rep and grades a correct answer without showing the explanation', async () => {
    let explanationCalls = 0
    vi.stubGlobal(
      'fetch',
      mockWarmup({
        set: [{ id: 'rep-pattern' }],
        reps: { 'rep-pattern': CHOICE_REP },
        verdictFor: (submission) => ({
          outcome: submission === 'Two pointers' ? 'PASSED' : 'FAILED',
          passed: submission === 'Two pointers' ? 1 : 0,
          total: 1,
          detail: '',
        }),
        onExplanation: () => {
          explanationCalls += 1
        },
      }) as unknown as typeof fetch,
    )

    render(<Warmup />)

    expect(await screen.findByRole('heading', { name: 'Pattern: sorted-pair sum' })).toBeInTheDocument()
    expect(screen.getByText('Rep 1 of 1')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Two pointers'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
    // A correct answer withholds the explanation; it is one keystroke away and not yet asked.
    expect(screen.queryByText(/two pointers are O\(1\)/i)).not.toBeInTheDocument()
    expect(explanationCalls).toBe(0)

    fireEvent.click(screen.getByRole('button', { name: 'Why is this the answer?' }))

    expect(await screen.findByText(/two pointers are O\(1\)/i)).toBeInTheDocument()
    // Asking is recorded via the explanation endpoint (issue #51).
    expect(explanationCalls).toBe(1)
  })

  it('grades a free-text predict-output rep', async () => {
    vi.stubGlobal(
      'fetch',
      mockWarmup({
        set: [{ id: 'rep-predict' }],
        reps: { 'rep-predict': FREE_TEXT_REP },
        verdictFor: (submission) => ({
          outcome: submission === 'cba!' ? 'PASSED' : 'FAILED',
          passed: submission === 'cba!' ? 1 : 0,
          total: 1,
          detail: '',
        }),
      }) as unknown as typeof fetch,
    )

    render(<Warmup />)
    await screen.findByRole('heading', { name: 'Predict output' })

    // A free-text rep shows a text box, not radio options.
    const box = screen.getByLabelText('Type the exact output')
    fireEvent.change(box, { target: { value: 'cba!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    expect(await screen.findByText('Correct')).toBeInTheDocument()
  })

  it('shows the explanation immediately on a wrong answer and re-queues the rep later in the set', async () => {
    vi.stubGlobal(
      'fetch',
      mockWarmup({
        set: [{ id: 'rep-pattern' }, { id: 'rep-predict' }],
        reps: { 'rep-pattern': CHOICE_REP, 'rep-predict': FREE_TEXT_REP },
        verdictFor: (_submission, id) => {
          // The choice rep is always answered wrong here, to exercise the re-queue.
          const correct = id === 'rep-predict'
          return {
            outcome: correct ? 'PASSED' : 'FAILED',
            passed: correct ? 1 : 0,
            total: 1,
            detail: '',
            explanation: correct ? undefined : 'Two pointers, because the array is sorted.',
          }
        },
      }) as unknown as typeof fetch,
    )

    render(<Warmup />)
    await screen.findByRole('heading', { name: 'Pattern: sorted-pair sum' })
    // The set starts at two reps.
    expect(screen.getByText('Rep 1 of 2')).toBeInTheDocument()

    fireEvent.click(screen.getByLabelText('Sliding window'))
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))

    // A wrong answer discloses the explanation on its own - no button needed.
    expect(await screen.findByText(/because the array is sorted/i)).toBeInTheDocument()
    // And it is re-queued: the set now has a third rep to come.
    await waitFor(() => expect(screen.getByText('Rep 1 of 3')).toBeInTheDocument())

    // Move through the second rep...
    fireEvent.click(screen.getByRole('button', { name: 'Next rep' }))
    await screen.findByRole('heading', { name: 'Predict output' })
    expect(screen.getByText('Rep 2 of 3')).toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Type the exact output'), { target: { value: 'cba!' } })
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }))
    await screen.findByText('Correct')

    // ...and the re-queued pattern rep comes back around as the third.
    fireEvent.click(screen.getByRole('button', { name: 'Next rep' }))
    expect(await screen.findByRole('heading', { name: 'Pattern: sorted-pair sum' })).toBeInTheDocument()
    expect(screen.getByText('Rep 3 of 3')).toBeInTheDocument()
  })

  it('reports when there is nothing to warm up on yet', async () => {
    vi.stubGlobal(
      'fetch',
      mockWarmup({ set: [], reps: {}, verdictFor: () => ({}) }) as unknown as typeof fetch,
    )

    render(<Warmup />)

    expect(await screen.findByRole('heading', { name: /nothing to warm up/i })).toBeInTheDocument()
  })
})
