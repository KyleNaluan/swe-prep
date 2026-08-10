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

  it('degrades to an inline message when the readiness picture cannot be read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, json: async () => ({ error: 'down' }) }) as Response),
    )
    render(<Readiness />)

    expect(await screen.findByText('down')).toBeInTheDocument()
  })
})
