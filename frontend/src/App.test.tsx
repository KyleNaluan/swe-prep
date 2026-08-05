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

const EXERCISE = {
  id: 'two-sum',
  title: 'Two Sum',
  statement: 'Return the indices of the two numbers that add up to target.',
  language: 'java',
  stub: 'class Solution {}',
}

function mockFetch(runResponse: unknown, runOk = true) {
  return vi.fn(async (url: string | URL | Request) => {
    const href = String(url)
    if (href.endsWith('/api/exercise')) {
      return { ok: true, json: async () => EXERCISE } as Response
    }
    if (href.endsWith('/api/exercise/run')) {
      return { ok: runOk, status: runOk ? 200 : 500, json: async () => runResponse } as Response
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

  it('loads and shows the exercise statement and stub', async () => {
    vi.stubGlobal('fetch', mockFetch({}))

    render(<App />)

    expect(await screen.findByRole('heading', { name: 'Two Sum' })).toBeInTheDocument()
    expect(screen.getByText(/two numbers that add up/i)).toBeInTheDocument()
    expect(screen.getByLabelText('editor')).toHaveValue('class Solution {}')
  })

  it('reports the passing-test count after a run', async () => {
    vi.stubGlobal(
      'fetch',
      mockFetch({ outcome: 'FAILED', passed: 3, total: 4, detail: '' }),
    )

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
})
