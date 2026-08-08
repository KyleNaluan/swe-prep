import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RolePicker from './RolePicker'

// The role picker is the whole user-facing surface of the family filter (issue #40): the user picks
// a named role, the choice is PUT to the backend, and the warm-up is asked to rebuild. It renders
// presets, not a checklist of family tags.

const STATUS = {
  presets: [
    { id: 'full-stack-ai-ml', label: 'Full-stack + AI/ML', families: ['AIML', 'BACKEND', 'FRONTEND'] },
    { id: 'backend', label: 'Backend', families: ['BACKEND'] },
    { id: 'everything', label: 'Everything', families: ['AIML', 'BACKEND', 'DATA'] },
  ],
  activeFamilies: [],
  currentPreset: 'everything',
  chosen: false,
}

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('RolePicker (issue #40)', () => {
  it('offers named role presets, not a checklist of families', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: true, json: async () => STATUS }) as Response),
    )
    render(<RolePicker />)

    // Every preset shows by its human label; there is no per-family checkbox.
    expect(await screen.findByRole('option', { name: 'Full-stack + AI/ML' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Backend' })).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).toBeNull()
  })

  it('PUTs the chosen preset and asks the warm-up to rebuild', async () => {
    let putBody: unknown = null
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      if (href.endsWith('/api/role') && (init?.method ?? 'GET') === 'PUT') {
        putBody = JSON.parse(String(init?.body))
        return {
          ok: true,
          json: async () => ({ ...STATUS, currentPreset: 'backend', chosen: true }),
        } as Response
      }
      return { ok: true, json: async () => STATUS } as Response
    })
    vi.stubGlobal('fetch', fetchMock)
    const onChange = vi.fn()
    render(<RolePicker onChange={onChange} />)

    const select = (await screen.findByRole('combobox')) as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'backend' } })

    await waitFor(() => expect(onChange).toHaveBeenCalled())
    expect(putBody).toEqual({ preset: 'backend' })
  })

  it('surfaces a failed save inline rather than reverting the select silently', async () => {
    const fetchMock = vi.fn(async (url: string | URL | Request, init?: RequestInit) => {
      const href = String(url)
      if (href.endsWith('/api/role') && (init?.method ?? 'GET') === 'PUT') {
        return { ok: false, status: 500, json: async () => ({ error: 'boom' }) } as Response
      }
      return { ok: true, json: async () => STATUS } as Response
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<RolePicker />)

    const select = (await screen.findByRole('combobox')) as HTMLSelectElement
    fireEvent.change(select, { target: { value: 'backend' } })

    // The status is already loaded, so the save error must show inline, not be swallowed.
    expect(await screen.findByRole('alert')).toHaveTextContent('Not saved')
  })

  it('degrades to a quiet note when the role status cannot be read', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => ({ ok: false, status: 500, json: async () => ({ error: 'down' }) }) as Response),
    )
    render(<RolePicker />)

    expect(await screen.findByText('Focus unavailable')).toBeInTheDocument()
  })
})
