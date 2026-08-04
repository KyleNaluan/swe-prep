import { render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

describe('App', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('shows the backend as up when the health endpoint reports UP', async () => {
    vi.mocked(fetch).mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'UP' }),
    } as Response)

    render(<App />)

    expect(await screen.findByText(/backend is UP/i)).toBeInTheDocument()
  })

  it('shows the backend as unreachable when the health call fails', async () => {
    vi.mocked(fetch).mockRejectedValue(new Error('network error'))

    render(<App />)

    expect(await screen.findByText(/backend is unreachable/i)).toBeInTheDocument()
  })
})
