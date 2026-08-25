import { useCallback, useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import {
  clearContentHashIfOpen,
  leaveContentEntry,
  pushContentEntry,
  useBrowseContentScroll,
  useContentPopState,
} from './contentNav'

// The browse <-> dedicated-content-page toggle Practice and Learn both use (issue
// carrying Direction A's "drill in, breadcrumb above" pattern into Direction C - see
// AGENTS.md's visual-redesign section), proven directly here once rather than only
// through Practice/Lesson's own integration tests - the same shape TreeBrowser.test.tsx
// already uses for the tree itself.

function Harness({ section }: { section: 'practice' | 'learn' }) {
  const [view, setView] = useState<'browse' | 'content'>('browse')
  const [id, setId] = useState<string | null>(null)
  useBrowseContentScroll(view)
  const onEnter = useCallback((enteredId: string) => {
    setId(enteredId)
    setView('content')
  }, [])
  const onLeave = useCallback(() => setView('browse'), [])
  useContentPopState(section, onEnter, onLeave)

  return (
    <div>
      <p>view: {view}</p>
      <p>id: {id ?? 'none'}</p>
      <button
        type="button"
        onClick={() => {
          setId('two-sum')
          setView('content')
          pushContentEntry(section, 'two-sum')
        }}
      >
        open two-sum
      </button>
      <button type="button" onClick={leaveContentEntry}>
        back to browse
      </button>
    </div>
  )
}

describe('contentNav (dedicated content pages)', () => {
  beforeEach(() => {
    // jsdom's window/history is shared across every test in this file - reset to a
    // clean base, matching a real fresh page load, before each test.
    window.history.replaceState(null, '', '/')
  })
  afterEach(() => cleanup())

  it('entering a content page pushes one history entry named for its section and id', () => {
    render(<Harness section="practice" />)
    fireEvent.click(screen.getByRole('button', { name: 'open two-sum' }))

    expect(window.location.hash).toBe('#/practice/two-sum')
    expect(window.history.state).toEqual({ view: 'content', section: 'practice', id: 'two-sum' })
  })

  it('leaving via history.back() flips the view back to browse, matching a breadcrumb click', async () => {
    render(<Harness section="practice" />)
    fireEvent.click(screen.getByRole('button', { name: 'open two-sum' }))
    expect(screen.getByText('view: content')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'back to browse' }))

    expect(await screen.findByText('view: browse')).toBeInTheDocument()
  })

  it("the browser's own Back/Forward buttons work naturally - a real popstate flips the view without any click", async () => {
    render(<Harness section="practice" />)
    fireEvent.click(screen.getByRole('button', { name: 'open two-sum' }))
    expect(screen.getByText('view: content')).toBeInTheDocument()

    // Simulate the browser's own Back button: history navigates and fires popstate
    // with no click on anything this component rendered.
    window.history.back()
    expect(await screen.findByText('view: browse')).toBeInTheDocument()

    // ...and Forward re-enters the same content page.
    window.history.forward()
    expect(await screen.findByText('view: content')).toBeInTheDocument()
    expect(screen.getByText('id: two-sum')).toBeInTheDocument()
  })

  it('a popstate carrying a different section is treated as leaving, never as entering', async () => {
    render(<Harness section="learn" />)
    fireEvent.click(screen.getByRole('button', { name: 'open two-sum' }))
    expect(screen.getByText('view: content')).toBeInTheDocument()

    // A stale entry belonging to Practice (e.g. left over from before the session
    // tab switched to Learn) must never be read as this Learn page's own content.
    window.dispatchEvent(
      new PopStateEvent('popstate', {
        state: { view: 'content', section: 'practice', id: 'two-sum' },
      }),
    )

    expect(await screen.findByText('view: browse')).toBeInTheDocument()
  })

  // Regression coverage for the "URL hash keeps pointing at a content page after
  // switching to Today/Readiness" bug: Session.tsx's tab switch unmounts Practice/Learn
  // without either ever popping its own content entry, so clearContentHashIfOpen is what
  // the tab switch calls instead - a direct, non-React-harness test of the mechanism
  // itself, the same shape the other tests here give pushContentEntry/leaveContentEntry.
  describe('clearContentHashIfOpen', () => {
    it('clears the hash and state in place, with no history-depth change, when a content page is open', () => {
      pushContentEntry('practice', 'two-sum')
      expect(window.location.hash).toBe('#/practice/two-sum')
      const before = window.history.length

      clearContentHashIfOpen()

      expect(window.location.hash).toBe('')
      expect(window.history.state).toBeNull()
      expect(window.history.length).toBe(before)
    })

    it('is a no-op when no content page is open', () => {
      window.history.replaceState(null, '', '/some/browse/path')
      const before = window.history.length

      clearContentHashIfOpen()

      expect(window.location.pathname).toBe('/some/browse/path')
      expect(window.history.length).toBe(before)
    })
  })
})
