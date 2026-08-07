import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Lesson from './Lesson'

const LESSONS = [
  { id: 'lesson-idx', title: 'Why an index is not used', domain: 'fundamentals', difficulty: 'MEDIUM', promptCount: 1 },
]

const DETAIL = {
  id: 'lesson-idx',
  title: 'Why an index is not used',
  statement: 'A B-tree index speeds lookups by key.',
  domain: 'fundamentals',
  difficulty: 'MEDIUM',
  prompts: [
    {
      prompt: 'Explain why a function-wrapped column skips the index.',
      modelAnswer: 'The index stores raw values, not the function output.',
    },
  ],
}

function mockFetch() {
  return vi.fn(async (url: string | URL | Request) => {
    const href = String(url)
    if (href.endsWith('/api/lessons')) return { ok: true, json: async () => LESSONS } as Response
    if (href.endsWith('/api/lessons/lesson-idx'))
      return { ok: true, json: async () => DETAIL } as Response
    throw new Error(`unexpected fetch to ${href}`)
  })
}

describe('Lesson renderer (issue #41/#46)', () => {
  beforeEach(() => vi.stubGlobal('fetch', vi.fn()))
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('shows a lesson with an ungraded self-explanation prompt that reveals its answer', async () => {
    vi.stubGlobal('fetch', mockFetch())
    render(<Lesson />)

    expect(await screen.findByRole('heading', { name: 'Why an index is not used' })).toBeInTheDocument()
    // The prompt is shown; its model answer is hidden until the reader reveals it.
    expect(
      screen.getByText('Explain why a function-wrapped column skips the index.'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/stores raw values/)).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Reveal the answer' }))

    expect(await screen.findByText(/stores raw values/)).toBeInTheDocument()
  })
})
