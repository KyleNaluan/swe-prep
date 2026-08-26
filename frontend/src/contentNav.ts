import { useEffect, useLayoutEffect, useRef } from 'react'

// Drives the browse <-> dedicated-content-page toggle Learn uses (Practice retired its
// use in the full-screen redesign - see AGENTS.md; this module stays generic over both
// 'practice'/'learn' sections for any future content-page surface), carrying Direction A's
// "three panes, drill left to right, breadcrumb above" pattern into Direction C's shipped
// design. The tree/grid pane is never unmounted across the toggle - Lesson.tsx renders
// it always, only hidden with CSS while a content page is open - so its own scroll
// container, expanded nodes and filters need no explicit save/restore at all: nothing
// here ever touches them. The two things that DO need explicit handling are the
// browser's own history (so back/forward work) and the page's own window scroll (since
// a content page renders where the tree used to be); this module owns both.

export type ContentPageView = 'browse' | 'content'

type ContentNavState = { view: 'content'; section: 'practice' | 'learn'; id: string }

// Pushes one history entry for entering a dedicated content page.
export function pushContentEntry(section: 'practice' | 'learn', id: string) {
  const state: ContentNavState = { view: 'content', section, id }
  window.history.pushState(state, '', `#/${section}/${encodeURIComponent(id)}`)
}

// Replaces the current history entry with a content one, adding no depth.
export function replaceContentEntry(section: 'practice' | 'learn', id: string) {
  const state: ContentNavState = { view: 'content', section, id }
  window.history.replaceState(state, '', `#/${section}/${encodeURIComponent(id)}`)
}

// The mount-time auto-pick each surface opens with (the scheduler's main exercise, the
// first lesson) enters a content page just like a tree click, but must never accumulate
// history depth on every remount (a tab switch away and back remounts the surface) or
// stack a fresh entry over the one the browser already restored on reload. It reads the
// browser's actual current state: any content-shaped entry already sitting here means a
// browse base exists beneath it (an earlier push this session, or a reload-restored one),
// so it replaces in place; only a truly fresh position (no content state yet) pushes the
// single browse-base -> content entry that `leaveContentEntry`'s one `back()` relies on.
export function enterAutoPickedContent(section: 'practice' | 'learn', id: string) {
  const current = window.history.state as ContentNavState | null
  if (current?.view === 'content') {
    replaceContentEntry(section, id)
  } else {
    pushContentEntry(section, id)
  }
}

// Returns to the browse view the same way a breadcrumb click and the browser's own
// Back button do. Entering a content page always pushes exactly one entry - the tree
// is hidden while a content page is open, so a real user can never reach a second
// content page without returning to browse first - so leaving is always exactly one
// `back()`, never a fresh push to some "browse" URL of our own.
export function leaveContentEntry() {
  window.history.back()
}

// Session.tsx's top-level tab switch (Today/Readiness/Practice/Learn) is not itself a
// history-tracked action - only entering/leaving a dedicated content page is. But a
// content page's own hash is the *current* history entry while it's open, and switching
// away from Practice/Learn unmounts it (Session renders exactly one surface per mode)
// without ever popping that entry, so the address bar is left pointing at a content page
// nothing renders anymore. Session.tsx calls this before switching to Today or Readiness
// (never between Practice and Learn - see switchMode's own comment there for why): if a
// content entry is currently open, it clears the hash in place (`replaceState`,
// deliberately not `leaveContentEntry`'s `back()`) - the address bar catches up with no
// back-navigation
// and no history-depth change, so the back/forward stack is exactly the size it was,
// just with a plain entry instead of a content one on top; a `back()` here would instead
// consume a step of "back" and strand a now-orphaned "forward" entry pointing at a
// content page that would render into whatever tab is current by the time it's reached,
// which is worse, not safer. A later re-entry into that section's content view (a tree
// click, or the mount-time auto-pick on switching back) pushes or replaces a fresh entry
// from there exactly as it always has - this never runs while a section is mounted, so
// it can never race `useContentPopState`'s own popstate handling.
export function clearContentHashIfOpen() {
  const state = window.history.state as ContentNavState | null
  if (state?.view === 'content') {
    window.history.replaceState(null, '', window.location.pathname + window.location.search)
  }
}

// Subscribes to popstate for one section: `onEnter(id)` fires when back/forward lands
// on that section's own content state, `onLeave()` fires for anything else - including
// the page-load entry underneath every entry this module ever pushes, and a state
// belonging to a different section (Practice and Learn are never both mounted at once
// under the session's tab model, but a stale entry from the other section could still
// be reachable via forward after a tab switch).
export function useContentPopState(
  section: 'practice' | 'learn',
  onEnter: (id: string) => void,
  onLeave: () => void,
) {
  useEffect(() => {
    function handlePopState(event: PopStateEvent) {
      const state = event.state as ContentNavState | null
      if (state?.view === 'content' && state.section === section) {
        onEnter(state.id)
      } else {
        onLeave()
      }
    }
    window.addEventListener('popstate', handlePopState)
    return () => window.removeEventListener('popstate', handlePopState)
  }, [section, onEnter, onLeave])
}

// The window-scroll half of position preservation. A content page always opens at the
// top, like a fresh page; returning to browse replays the last scroll position seen
// while browsing, tracked passively rather than captured only at the moment of the
// click - that is what makes it correct however the return happens (breadcrumb, the
// browser's own Back button, or forward re-entering a content page and then leaving
// it again).
export function useBrowseContentScroll(view: ContentPageView) {
  const browseScrollRef = useRef(0)

  useEffect(() => {
    if (view !== 'browse') return
    function handleScroll() {
      browseScrollRef.current = window.scrollY
    }
    window.addEventListener('scroll', handleScroll, { passive: true })
    return () => window.removeEventListener('scroll', handleScroll)
  }, [view])

  useLayoutEffect(() => {
    window.scrollTo(0, view === 'content' ? 0 : browseScrollRef.current)
  }, [view])
}
