import { useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TreeBrowser, { type FilterGroup, type TreeItem } from './TreeBrowser'

// issue #90: the shared tiered navigation (Area -> Pattern/Concept) both Practice and
// Learn use, with difficulty (and family) as filters, never a tier. The captain's two
// binding refinements this file exists to prove directly, once, rather than only
// through Practice/Lesson's own integration tests:
//
//   1. "whenever I'm already on a pattern section, but then click on a difficulty
//      filter, it'll reset the view to the overall track view, which is annoying" -
//      applying or clearing a filter must never reset the current tree selection.
//   2. Filter groups are visually separate labeled sections (the shopping-site
//      faceted pattern), never one undifferentiated chip row mixing axes.

const ITEMS: TreeItem[] = [
  { id: 'a1', title: 'Two Sum', domain: 'algorithms', difficulty: 'EASY', topics: ['array', 'hash-map'], family: [] },
  { id: 'a2', title: 'Climbing Stairs', domain: 'algorithms', difficulty: 'EASY', topics: ['dynamic-programming'], family: [] },
  { id: 'a3', title: '3Sum', domain: 'algorithms', difficulty: 'MEDIUM', topics: ['array', 'two-pointers'], family: [] },
  { id: 'b1', title: 'Top Customers', domain: 'databases', difficulty: 'EASY', topics: ['joins'], family: [] },
]

const FILTER_GROUPS: FilterGroup[] = [
  {
    key: 'difficulty',
    label: 'Difficulty',
    options: [
      { value: 'EASY', label: 'Easy' },
      { value: 'MEDIUM', label: 'Medium' },
      { value: 'HARD', label: 'Hard' },
    ],
  },
]

function Harness({ groups = FILTER_GROUPS }: { groups?: FilterGroup[] }) {
  const [filters, setFilters] = useState({
    difficulty: new Set(['EASY', 'MEDIUM', 'HARD']),
  })
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const toggle = (key: string, value: string) => {
    setFilters((prev) => {
      const set = new Set(prev[key as keyof typeof prev] ?? [])
      if (set.has(value)) set.delete(value)
      else set.add(value)
      return { ...prev, [key]: set }
    })
  }
  return (
    <TreeBrowser
      items={ITEMS}
      filterGroups={groups}
      activeFilters={filters}
      onToggleFilter={toggle}
      selectedId={selectedId}
      onSelect={(item) => setSelectedId(item.id)}
      findLabel="Find a problem"
      emptyMessage="No items."
      sectionLabel="Practice"
      itemNoun="problem"
    />
  )
}

afterEach(() => cleanup())

describe('TreeBrowser (issue #90)', () => {
  it('renders each filter group in its own labeled fieldset, not one mixed row', () => {
    const groups: FilterGroup[] = [
      ...FILTER_GROUPS,
      {
        key: 'family',
        label: 'Family',
        options: [{ value: 'CORE', label: 'Core' }],
      },
    ]
    render(<Harness groups={groups} />)

    const difficultyGroup = screen.getByText('Difficulty').closest('fieldset')
    const familyGroup = screen.getByText('Family').closest('fieldset')
    expect(difficultyGroup).not.toBeNull()
    expect(familyGroup).not.toBeNull()
    expect(difficultyGroup).not.toBe(familyGroup)
    // Easy belongs to the difficulty fieldset, not the family one.
    expect(difficultyGroup?.contains(screen.getByRole('button', { name: 'Easy' }))).toBe(true)
    expect(familyGroup?.contains(screen.getByRole('button', { name: 'Easy' }))).toBe(false)
  })

  it('drilling into a pattern then toggling a difficulty filter never resets the pattern view', () => {
    render(<Harness />)

    // Algorithms (the largest domain) is open by default; drill into the "array"
    // pattern node specifically - not an item card whose own topic note also
    // happens to contain the word "Array".
    fireEvent.click(screen.getByRole('button', { name: /^Array/ }))

    // Confirm we are on the pattern view (only the two array-tagged items show).
    expect(screen.getByRole('button', { name: /Two Sum/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /3Sum/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Climbing Stairs/ })).not.toBeInTheDocument()

    // Excluding Hard (a no-op here, nothing is Hard) must not move us back to the
    // domain-level view - the exact regression the captain flagged.
    fireEvent.click(screen.getByRole('button', { name: 'Hard' }))

    expect(screen.getByRole('button', { name: /Two Sum/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /3Sum/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Climbing Stairs/ })).not.toBeInTheDocument()

    // Excluding Medium actually narrows the still-open pattern view in place - 3Sum
    // (Medium) drops out, Two Sum (Easy) stays, and Climbing Stairs stays excluded.
    fireEvent.click(screen.getByRole('button', { name: 'Medium' }))

    expect(screen.getByRole('button', { name: /Two Sum/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /3Sum/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Climbing Stairs/ })).not.toBeInTheDocument()
  })

  it('selecting an item calls onSelect with that item', () => {
    render(<Harness />)

    // Algorithms is open by default (the largest domain).
    fireEvent.click(screen.getByRole('button', { name: /Two Sum/ }))

    expect(screen.getByRole('button', { name: /Two Sum/ })).toHaveAttribute('aria-current', 'true')
  })

  // A consumer's own dedicated content-page breadcrumb (issue carrying Direction A's
  // page pattern into Direction C - AGENTS.md) needs the tree's own drill state at the
  // moment of the click, since it never re-derives TreeBrowser's grouping itself.
  it('passes the open domain and pattern as onSelect context, and omits the pattern for a domain-level or search pick', () => {
    const onSelect = vi.fn()
    render(
      <TreeBrowser
        items={ITEMS}
        filterGroups={FILTER_GROUPS}
        activeFilters={{ difficulty: new Set(['EASY', 'MEDIUM', 'HARD']) }}
        onToggleFilter={vi.fn()}
        selectedId={null}
        onSelect={onSelect}
        findLabel="Find a problem"
        emptyMessage="No items."
        sectionLabel="Practice"
        itemNoun="problem"
      />,
    )

    // Domain-level pick (no pattern drilled into): algorithms is open by default.
    fireEvent.click(screen.getByRole('button', { name: /Climbing Stairs/ }))
    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({ id: 'a2' }),
      { domain: 'algorithms', pattern: null },
    )

    // Drill into a pattern, then pick from it: both domain and pattern are reported.
    fireEvent.click(screen.getByRole('button', { name: /^Array/ }))
    fireEvent.click(screen.getByRole('button', { name: /Two Sum/ }))
    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({ id: 'a1' }),
      { domain: 'algorithms', pattern: 'array' },
    )

    // A cross-domain search result has no single pattern context.
    fireEvent.change(screen.getByLabelText('Find a problem'), { target: { value: 'Top Customers' } })
    fireEvent.click(screen.getByRole('button', { name: /Top Customers/ }))
    expect(onSelect).toHaveBeenLastCalledWith(
      expect.objectContaining({ id: 'b1' }),
      { domain: 'databases', pattern: null },
    )
  })

  it('opens the domain of a selection that arrives asynchronously after the items load', () => {
    // Practice sets its items from the catalog fetch, then its selectedId from a
    // second scheduler call (pickMain -> /api/challenges/next), so the tree first
    // renders with items but selectedId still null. The scheduler-picked exercise's
    // real domain must win once it resolves, not be stranded on the largest-domain
    // default.
    function AsyncHarness() {
      const [selectedId, setSelectedId] = useState<string | null>(null)
      return (
        <>
          <button type="button" onClick={() => setSelectedId('b1')}>
            resolve pick
          </button>
          <TreeBrowser
            items={ITEMS}
            filterGroups={FILTER_GROUPS}
            activeFilters={{ difficulty: new Set(['EASY', 'MEDIUM', 'HARD']) }}
            onToggleFilter={vi.fn()}
            selectedId={selectedId}
            onSelect={vi.fn()}
            findLabel="Find a problem"
            emptyMessage="No items."
            sectionLabel="Practice"
            itemNoun="problem"
          />
        </>
      )
    }
    render(<AsyncHarness />)

    // Algorithms (the largest domain) opens first; the databases item isn't in the
    // pane yet because selectedId is still null.
    expect(screen.queryByRole('button', { name: /Top Customers/ })).not.toBeInTheDocument()

    // The scheduler pick resolves on a later render, in the databases domain.
    fireEvent.click(screen.getByRole('button', { name: 'resolve pick' }))

    expect(screen.getByRole('button', { name: /Top Customers/ })).toBeInTheDocument()
  })

  it('searching finds an item across domains without requiring the right one to be open', () => {
    render(<Harness />)
    // The databases domain is not open by default (algorithms sorts first, more items).
    expect(screen.queryByRole('button', { name: /Top Customers/ })).not.toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Find a problem'), { target: { value: 'Top Customers' } })

    expect(screen.getByRole('button', { name: /Top Customers/ })).toBeInTheDocument()
  })

  it('shows a message and no crash when there are no items', () => {
    function Empty() {
      return (
        <TreeBrowser
          items={[]}
          filterGroups={FILTER_GROUPS}
          activeFilters={{}}
          onToggleFilter={vi.fn()}
          selectedId={null}
          onSelect={vi.fn()}
          findLabel="Find a problem"
          emptyMessage="No items yet."
          sectionLabel="Practice"
          itemNoun="problem"
        />
      )
    }
    render(<Empty />)
    expect(screen.getByText('No items yet.')).toBeInTheDocument()
  })
})

// The third display mode (captain-approved full-screen redesign, issue:
// swe-practice-fs-build): Practice's "Problem List" overlay sidebar has no room for
// the desktop `.pane` grid, so items render as a single condensed column instead -
// rich rows (a difficulty chip and a solved checkmark) nested under Area/Pattern.
describe('TreeBrowser sidebarMode (issue: swe-practice-fs-build)', () => {
  const UNTAGGED: TreeItem = {
    id: 'u1',
    title: 'Untagged Problem',
    domain: 'algorithms',
    difficulty: 'HARD',
    topics: [],
    family: [],
  }

  function SidebarHarness({ solvedIds }: { solvedIds?: Set<string> }) {
    const [filters, setFilters] = useState({ difficulty: new Set(['EASY', 'MEDIUM', 'HARD']) })
    const [selectedId, setSelectedId] = useState<string | null>(null)
    const [lastContext, setLastContext] = useState<{ domain: string; pattern: string | null } | null>(
      null,
    )
    const toggle = (key: string, value: string) => {
      setFilters((prev) => {
        const set = new Set(prev[key as keyof typeof prev] ?? [])
        if (set.has(value)) set.delete(value)
        else set.add(value)
        return { ...prev, [key]: set }
      })
    }
    return (
      <>
        <p>context: {lastContext ? `${lastContext.domain}/${lastContext.pattern ?? 'none'}` : 'none'}</p>
        <TreeBrowser
          sidebarMode
          items={[...ITEMS, UNTAGGED]}
          filterGroups={FILTER_GROUPS}
          activeFilters={filters}
          onToggleFilter={toggle}
          selectedId={selectedId}
          onSelect={(item, context) => {
            setSelectedId(item.id)
            setLastContext(context)
          }}
          findLabel="Find a problem"
          emptyMessage="No items."
          sectionLabel="Practice"
          itemNoun="problem"
          solvedIds={solvedIds}
        />
      </>
    )
  }

  it('never renders the desktop .pane grid or crumb', () => {
    render(<SidebarHarness />)
    expect(document.querySelector('.pane')).toBeNull()
    expect(document.querySelector('.crumb')).toBeNull()
  })

  it('nests rich item rows (chip + checkmark) under an expanded pattern, and reports its context', () => {
    render(<SidebarHarness solvedIds={new Set(['a2'])} />)

    fireEvent.click(screen.getByRole('button', { name: /^Array/ }))
    const row = screen.getByRole('button', { name: /Two Sum/ })
    expect(row.querySelector('.chipx')).toHaveTextContent('Easy')
    // Not solved: the checkmark is present but pending (empty), not done.
    expect(row.querySelector('.solved-check')).toHaveClass('pending')

    fireEvent.click(row)
    expect(screen.getByText('context: algorithms/array')).toBeInTheDocument()
  })

  it('shows a done checkmark for a solved item', () => {
    render(<SidebarHarness solvedIds={new Set(['a2'])} />)
    fireEvent.click(screen.getByRole('button', { name: /^Dynamic programming/ }))
    const row = screen.getByRole('button', { name: /Climbing Stairs/ })
    expect(row.querySelector('.solved-check')).toHaveClass('done')
  })

  // An item with no topic tags never appears under any pattern node (groupByPattern's
  // own rule) - without a domain-level fallback it would be entirely unreachable in
  // the sidebar, since there is no `.pane` grid left to show it in either.
  it('keeps an untagged item reachable directly under its open domain, with no pattern picked', () => {
    render(<SidebarHarness />)
    // Algorithms is open by default (the largest domain) and no pattern is picked yet.
    expect(screen.getByRole('button', { name: /Untagged Problem/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /Untagged Problem/ }))
    expect(screen.getByText('context: algorithms/none')).toBeInTheDocument()
  })

  it('hides the domain-level fallback list once a pattern is picked, showing only that pattern\'s items', () => {
    render(<SidebarHarness />)
    expect(screen.getByRole('button', { name: /Untagged Problem/ })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: /^Array/ }))

    expect(screen.queryByRole('button', { name: /Untagged Problem/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Two Sum/ })).toBeInTheDocument()
  })

  it('searching shows a flat rich-row list across domains, with no pattern context', () => {
    render(<SidebarHarness />)
    fireEvent.change(screen.getByLabelText('Find a problem'), { target: { value: 'Top Customers' } })

    const row = screen.getByRole('button', { name: /Top Customers/ })
    expect(row.querySelector('.chipx')).toHaveTextContent('Easy')
    fireEvent.click(row)
    expect(screen.getByText('context: databases/none')).toBeInTheDocument()
  })
})
