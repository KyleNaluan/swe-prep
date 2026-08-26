import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import Readiness from './Readiness'

// The honest readiness picture (issue #45): a primary progress surface built entirely from
// plain "X of Y" counts and a bare self-check count - no score, no badge, no level - and the
// three-way separation (objective axes vs concepts-covered vs self-check) is visible in the
// rendered output, not just in the data shape.

const SUMMARY = {
  checksToCriterion: { achieved: 4, total: 10 },
  solvedCold: { achieved: 1, total: 4 },
  conceptsCovered: { achieved: 2, total: 5 },
  selfCheckExplainedCount: 3,
  families: [
    { family: 'CORE', checksToCriterion: { achieved: 4, total: 8 }, solvedCold: { achieved: 0, total: 0 } },
    { family: 'BACKEND', checksToCriterion: { achieved: 0, total: 2 }, solvedCold: { achieved: 1, total: 4 } },
    { family: 'AIML', checksToCriterion: { achieved: 0, total: 0 }, solvedCold: { achieved: 0, total: 0 } },
  ],
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Readiness (issue #45)', () => {
  it('renders the objective axes and the concepts-covered axis as plain counts', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => SUMMARY }) as Response),
    )
    const { container } = render(<Readiness />)

    expect(await screen.findByText('4/10')).toBeInTheDocument()
    expect(screen.getAllByText('1/4').length).toBeGreaterThan(0)
    expect(screen.getByText('2/5')).toBeInTheDocument()
    // No invented currency anywhere on the surface (the copy names what it is not, so
    // "points"/"badges"/"levels" legitimately appear once each in that disclaimer).
    expect(container.textContent).not.toMatch(/\bxp\b/i)
  })

  it('keeps the self-check count separate from the objective competence axes', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => SUMMARY }) as Response),
    )
    render(<Readiness />)

    expect(await screen.findByRole('heading', { name: 'Explained 3 concepts' })).toBeInTheDocument()
    expect(
      screen.getByText(/never added into the checks or challenges above/i),
    ).toBeInTheDocument()
    // The self-check count never shows up folded into checksToCriterion's 4/10.
    expect(screen.queryByText('7/10')).toBeNull()
  })

  it('shows a per-family breakdown, including always-active families with no content', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => SUMMARY }) as Response),
    )
    render(<Readiness />)

    expect(await screen.findByText('Backend')).toBeInTheDocument()
    expect(screen.getByText('Core')).toBeInTheDocument()
    // A selectable family with nothing tagged/attempted is not shown as a false zero.
    expect(screen.queryByText('AI/ML')).toBeNull()
  })

  it('never frames the streak as a loss when it is zero', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => SUMMARY }) as Response),
    )
    const { container } = render(<Readiness streak={0} />)

    expect(await screen.findByText(/no streak yet/i)).toBeInTheDocument()
    expect(container.textContent).not.toMatch(/broken|lost|reset/i)
  })

  it('shows shaky and stale topics as plain lists when present (issue #22)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          ({
            ok: true,
            json: async () => ({
              ...SUMMARY,
              shakyTopics: ['graphs', 'intervals'],
              staleTopics: [{ topic: 'dynamic programming', daysSinceTouched: 21 }],
            }),
          }) as Response,
      ),
    )
    render(<Readiness />)

    expect(await screen.findByText(/graphs, intervals/)).toBeInTheDocument()
    expect(screen.getByText(/dynamic programming \(21d\)/)).toBeInTheDocument()
  })

  it('shows neither topic-flag section when both lists are empty', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(
        async () =>
          ({ ok: true, json: async () => ({ ...SUMMARY, shakyTopics: [], staleTopics: [] }) }) as Response,
      ),
    )
    const { container } = render(<Readiness />)

    await screen.findByText('4/10')
    expect(container.querySelector('.topic-flags')).toBeNull()
  })

  it('degrades to an inline message when the readiness picture cannot be read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, json: async () => ({ error: 'down' }) }) as Response),
    )
    render(<Readiness />)

    expect(await screen.findByText('down')).toBeInTheDocument()
  })
})

// The attempt-history table, relocated here from Practice so that surface could become
// a full-screen workspace (captain-approved redesign, issue: swe-practice-fs-build).
// Placed below "By family" per the captain's final round-2 decision ("variant A...
// keep it at a set size and make it a scrollable component"), with the outcome-pill
// styling from the mockup.
describe('Readiness history (moved from Practice, issue: swe-practice-fs-build)', () => {
  const ATTEMPTS = [
    {
      id: 'a1',
      exerciseId: 'two-sum',
      exerciseTitle: 'Two Sum',
      domain: 'algorithms',
      form: 'CHALLENGE',
      outcome: 'SOLVED',
      startedAt: '2026-08-06T10:00:00Z',
      endedAt: '2026-08-06T10:05:00Z',
      submissionCount: 3,
      hintsTaken: 1,
      failingCaseRevealed: false,
      explanationRequested: false,
      solutionSeen: false,
    },
    {
      id: 'a2',
      exerciseId: 'merge-lists',
      exerciseTitle: 'Merge Two Sorted Lists',
      domain: 'algorithms',
      form: 'CHALLENGE',
      outcome: 'ABANDONED',
      startedAt: '2026-08-05T10:00:00Z',
      endedAt: null,
      submissionCount: 2,
      hintsTaken: 0,
      failingCaseRevealed: false,
      explanationRequested: false,
      solutionSeen: true,
    },
  ]

  function mockFetchWithAttempts(attempts: unknown) {
    return vi.fn(async (url: string | URL | Request) => {
      const href = String(url)
      if (href.endsWith('/api/attempts')) return { ok: true, json: async () => attempts } as Response
      return { ok: true, json: async () => SUMMARY } as Response
    })
  }

  it('renders the history table below "By family", with outcome pills', async () => {
    vi.stubGlobal('fetch', mockFetchWithAttempts(ATTEMPTS))
    render(<Readiness />)

    expect(await screen.findByRole('heading', { name: 'History' })).toBeInTheDocument()
    expect(screen.getByText('Two Sum')).toBeInTheDocument()
    expect(screen.getByText('Merge Two Sorted Lists')).toBeInTheDocument()
    expect(screen.getByText('solved')).toHaveClass('outcome', 'solved')
    expect(screen.getByText('abandoned')).toHaveClass('outcome', 'abandoned')
    expect(screen.getByText('yes')).toBeInTheDocument() // Merge's own solution-seen cell

    // Placed after "By family" in the DOM, per the captain's final decision (variant A).
    const familyHeading = screen.getByRole('heading', { name: 'By family' })
    const historyHeading = screen.getByRole('heading', { name: 'History' })
    expect(
      familyHeading.compareDocumentPosition(historyHeading) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('shows nothing when there is no history yet', async () => {
    vi.stubGlobal('fetch', mockFetchWithAttempts([]))
    render(<Readiness />)

    await screen.findByText('4/10')
    expect(screen.queryByRole('heading', { name: 'History' })).not.toBeInTheDocument()
  })

  it('degrades to an empty history rather than blanking the readiness picture when the attempt fetch fails', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (url: string | URL | Request) => {
        const href = String(url)
        if (href.endsWith('/api/attempts')) return { ok: false, status: 500, json: async () => ({}) } as Response
        return { ok: true, json: async () => SUMMARY } as Response
      }),
    )
    render(<Readiness />)

    expect(await screen.findByText('4/10')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'History' })).not.toBeInTheDocument()
  })
})
