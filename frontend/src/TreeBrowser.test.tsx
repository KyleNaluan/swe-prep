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
