import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { defaultDomainLabel, difficultyTone, topicLabel } from './treeLabels'

// The tiered navigation Practice and Learn share (issue #90/#7's map: "tiered/under
// categories" - the flat 583-item dropdowns this replaces lived in Practice.tsx's
// `<select id="exercise-select">` and Lesson.tsx's `<select id="lesson-select">`).
//
// The tiers are Area -> Pattern/Concept, per the captain's binding ruling: difficulty
// (and family) are FILTERS, never a navigation tier - a problem/concept can carry
// several topic tags and appears under every one of them on purpose (Two Sum is both
// "array" and "hash-map"), which is what makes "find me another two-pointers item"
// work. A tier with only three values (difficulty) or that fragments the majority of
// the catalog into one bucket would make a poor shelf; it makes a good filter instead.
//
// Position-preserving filters (the captain's refinement: "whenever I'm already on a
// pattern section, but then click on a difficulty filter, it'll reset the view... that
// is annoying") is structural here, not a special case to remember: `openDomain`,
// `openPattern` and the Find text are this component's OWN state, and a filter toggle
// is a prop change from the parent - it never touches that state, so re-filtering
// happens in place. The one thing that must never be done is deriving the "current"
// domain/pattern from the filtered set on every render; it is only ever set by an
// explicit click (or once, on first load).

export type TreeItem = {
  id: string
  title: string
  domain: string
  difficulty: string
  // Every topic tag this item carries - deliberately not just the first, so an item
  // appears under every pattern it belongs to (see the module doc above).
  topics: string[]
  // Role-family tags (design revision t3 section 2); untagged (empty) content is
  // always-eligible substrate for the family filter, never suppressed by it.
  family: string[]
}

// One labeled facet group (the captain's refinement: difficulty and family are
// visually separate groups, "the shopping-site faceted pattern", never one
// undifferentiated chip row mixing both axes). A chip is active (included) by
// default; clicking it excludes that value - the strike-through convention.
export type FilterGroup = {
  key: string
  label: string
  options: { value: string; label: string }[]
}

export type TreeBrowserProps = {
  items: TreeItem[]
  domainLabel?: (domain: string) => string
  filterGroups: FilterGroup[]
  activeFilters: Record<string, Set<string>>
  onToggleFilter: (groupKey: string, value: string) => void
  selectedId: string | null
  // Fires on a click on an item card. `context` is the tree's own drill state at the
  // moment of the click - the domain and pattern (topic) node open when the item was
  // picked - so a consumer's dedicated content page can render a breadcrumb ("Practice
  // > <area> > <pattern> > <item>") without re-deriving TreeBrowser's own grouping.
  // `pattern` is null when the pick came from a domain-level view or a cross-domain
  // search result, where no single pattern context applies.
  onSelect: (item: TreeItem, context: { domain: string; pattern: string | null }) => void
  findLabel: string
  findPlaceholder?: string
  emptyMessage: string
  // The section name shown in the breadcrumb ("Practice" / "Learn").
  sectionLabel: string
  // What one item is called, for the pane's descriptive copy ("problem" / "concept").
  itemNoun: string
  // Real per-item completion, when the surface actually has it (Practice has solved
  // attempt history; Learn currently does not track per-lesson read state on this
  // screen). Omitted entirely rather than faked - no per-node bar or badge renders
  // without it, since an invented progress signal is worse than none (issue #7).
  solvedIds?: Set<string>
  // Renders a compact list of item buttons directly under a pattern node once it is
  // expanded, in addition to the existing `.pane` item grid (issue #90 follow-on,
  // captain decision "learn-nav-item-select"). Lesson.tsx's single, always-mounted
  // TreeBrowser instance passes this - it is the SAME instance browse mode renders,
  // so the compact list exists in the DOM in both modes, but `.nav-item-list` is
  // `display: none` by default in App.css and only shown inside `.content-nav` at the
  // nav-rail breakpoint, where `.pane` (the item grid, the only other place a
  // lesson-select button rendered) is itself hidden - without this, the wide-viewport
  // persistent nav rail could drill Area/Pattern but never actually open a different
  // lesson. Left `undefined`/`false` (the default) for Practice and every existing
  // caller, so nothing here changes their rendered item buttons.
  compactNavList?: boolean
  // The third display mode (captain-approved full-screen redesign, issue:
  // swe-practice-fs-build): a single condensed column - no `.pane` item grid at all -
  // for Practice's "Problem List" overlay sidebar, which has no room for the desktop
  // two-pane layout. Distinct from `compactNavList` (Learn's plain-title nav rail,
  // shown *alongside* `.pane` at a wide breakpoint): sidebarMode replaces `.pane`
  // entirely, at every viewport size, and its item rows carry the same difficulty chip
  // and solved checkmark the grid cards do, since a slide-over panel is the only place
  // those items render at all. Default `false`/`undefined` for every existing caller.
  sidebarMode?: boolean
}

function passesFilters(
  item: TreeItem,
  filterGroups: FilterGroup[],
  activeFilters: Record<string, Set<string>>,
): boolean {
  return filterGroups.every((group) => {
    const active = activeFilters[group.key]
    if (!active) return true
    if (group.key === 'family') {
      // Untagged content is always-eligible substrate (matches the server-side role
      // filter's own rule), never suppressed by a family exclusion.
      return item.family.length === 0 || item.family.some((f) => active.has(f))
    }
    return active.has(item.difficulty)
  })
}

type DomainGroup = { domain: string; items: TreeItem[] }
type PatternGroup = { topic: string; items: TreeItem[] }

function groupByDomain(items: TreeItem[]): DomainGroup[] {
  const byDomain = new Map<string, TreeItem[]>()
  for (const item of items) {
    const list = byDomain.get(item.domain) ?? []
    list.push(item)
    byDomain.set(item.domain, list)
  }
  return [...byDomain.entries()]
    .map(([domain, list]) => ({ domain, items: list }))
    .sort((a, b) => b.items.length - a.items.length || a.domain.localeCompare(b.domain))
}

function groupByPattern(items: TreeItem[]): PatternGroup[] {
  const byTopic = new Map<string, TreeItem[]>()
  for (const item of items) {
    for (const topic of item.topics) {
      const list = byTopic.get(topic) ?? []
      list.push(item)
      byTopic.set(topic, list)
    }
  }
  return [...byTopic.entries()]
    .map(([topic, list]) => ({ topic, items: list }))
    .sort((a, b) => b.items.length - a.items.length || a.topic.localeCompare(b.topic))
}

function completion(items: TreeItem[], solvedIds: Set<string> | undefined): number | null {
  if (!solvedIds || items.length === 0) return null
  const solved = items.filter((item) => solvedIds.has(item.id)).length
  return solved / items.length
}

function TreeBrowser({
  items,
  domainLabel = defaultDomainLabel,
  filterGroups,
  activeFilters,
  onToggleFilter,
  selectedId,
  onSelect,
  findLabel,
  findPlaceholder,
  emptyMessage,
  sectionLabel,
  itemNoun,
  solvedIds,
  compactNavList,
  sidebarMode,
}: TreeBrowserProps) {
  const [find, setFind] = useState('')
  const [openDomain, setOpenDomain] = useState<string | null>(null)
  const [openPattern, setOpenPattern] = useState<string | null>(null)
  // Once the initial domain is chosen for a real reason - a resolved selection, or the
  // user opening a node themselves - it is pinned so no later render (a filter toggle,
  // items re-mapping to a fresh array) can move the open node out from under them.
  const initialDomainPinned = useRef(false)

  const filtered = useMemo(
    () => items.filter((item) => passesFilters(item, filterGroups, activeFilters)),
    [items, filterGroups, activeFilters],
  )
  const domains = useMemo(() => groupByDomain(filtered), [filtered])

  // Pick the initial open domain from the current selection, then pin it - never
  // re-derived once pinned, or a filter toggle would silently move the tree's open
  // node out from under the solver (the captain's refinement this whole design exists
  // to satisfy). The selection can arrive asynchronously *after* the catalog does -
  // Practice sets its items from the catalog fetch, then its selectedId from a second,
  // separate scheduler call (pickMain -> /api/challenges/next). So until a selection
  // resolves we only *tentatively* open the largest domain and stay unpinned, letting
  // the scheduler-picked exercise's real domain win the moment selectedId flips from
  // null to a value rather than being stranded on the largest-domain default.
  useEffect(() => {
    if (initialDomainPinned.current || items.length === 0) return
    const selectedDomain = selectedId
      ? items.find((item) => item.id === selectedId)?.domain
      : undefined
    if (selectedDomain) {
      setOpenDomain(selectedDomain)
      initialDomainPinned.current = true
      return
    }
    setOpenDomain((current) => current ?? groupByDomain(items)[0]?.domain ?? null)
  }, [items, selectedId])

  const query = find.trim().toLowerCase()
  const searching = query.length > 0

  if (items.length === 0) {
    return <p className="status loading">{emptyMessage}</p>
  }

  const current = domains.find((d) => d.domain === openDomain) ?? domains[0]
  const patterns = current ? groupByPattern(current.items) : []
  const activePattern = openPattern ? patterns.find((p) => p.topic === openPattern) : undefined

  const searchResults = searching
    ? filtered.filter(
        (item) =>
          item.title.toLowerCase().includes(query) ||
          item.topics.some((t) => t.toLowerCase().includes(query)),
      )
    : []

  const paneItems = searching ? searchResults : (activePattern?.items ?? current?.items ?? [])

  const findAndFilters = (
    <>
      <label className="find">
        <span className="lbl">Find</span>
        <input
          type="search"
          aria-label={findLabel}
          placeholder={findPlaceholder}
          value={find}
          onChange={(event) => setFind(event.target.value)}
        />
      </label>

      {filterGroups.map((group) => (
        <fieldset className="filtergroup" key={group.key}>
          <legend className="lbl">{group.label}</legend>
          <div className="filterrow">
            {group.options.map((option) => {
              const active = activeFilters[group.key]?.has(option.value) ?? true
              return (
                <button
                  key={option.value}
                  type="button"
                  className="fchip"
                  aria-pressed={active}
                  onClick={() => onToggleFilter(group.key, option.value)}
                >
                  {option.label}
                </button>
              )
            })}
          </div>
        </fieldset>
      ))}
    </>
  )

  // Shared by both display modes: the Area -> Pattern tree walk itself never differs -
  // only what nests under an expanded pattern does (nothing, `compactNavList`'s plain
  // nav-rail titles, or sidebarMode's rich rows), via `patternExtra`, and (sidebarMode
  // only) what renders directly under an open domain with no pattern picked yet, via
  // the optional `domainExtra` - mirroring `.pane`'s own `activePattern?.items ??
  // current?.items` fallback, so an untagged item (no topics, so it never appears
  // under any pattern node) stays reachable in the sidebar exactly as it is in the
  // grid, rather than becoming unreachable once `.pane` itself is gone.
  function renderDomainTree(
    patternExtra: (pattern: PatternGroup, patternOpen: boolean) => ReactNode,
    domainExtra?: (group: DomainGroup) => ReactNode,
  ) {
    return (
      <div className="treelist" role="tree">
        {domains.map((group) => {
          const open = group.domain === openDomain
          const pct = completion(group.items, solvedIds)
          return (
            <div className="treegroup" key={group.domain}>
              <button
                type="button"
                className={`node${open ? ' open' : ''}`}
                aria-current={open && !openPattern}
                onClick={() => {
                  initialDomainPinned.current = true
                  setOpenDomain(open ? null : group.domain)
                  setOpenPattern(null)
                }}
              >
                <span className="tw" aria-hidden="true">
                  ▶
                </span>
                <span className="nm">{domainLabel(group.domain)}</span>
                {pct !== null && (
                  <span className="prog">
                    <i style={{ width: `${(pct * 100).toFixed(0)}%` }} />
                  </span>
                )}
                <span className="ct">{group.items.length}</span>
              </button>
              {open &&
                groupByPattern(group.items).map((pattern) => {
                  const ppct = completion(pattern.items, solvedIds)
                  const patternOpen = openPattern === pattern.topic
                  return (
                    <div key={pattern.topic}>
                      <button
                        type="button"
                        className="node l2"
                        aria-current={patternOpen}
                        onClick={() => {
                          initialDomainPinned.current = true
                          setOpenPattern(patternOpen ? null : pattern.topic)
                        }}
                      >
                        <span className="nm">{topicLabel(pattern.topic)}</span>
                        {ppct !== null && (
                          <span className="prog">
                            <i style={{ width: `${(ppct * 100).toFixed(0)}%` }} />
                          </span>
                        )}
                        <span className="ct">{pattern.items.length}</span>
                      </button>
                      {patternExtra(pattern, patternOpen)}
                    </div>
                  )
                })}
              {open && domainExtra?.(group)}
            </div>
          )
        })}
      </div>
    )
  }

  // The rich row sidebarMode uses in place of both the plain-title `.nav-item`
  // (compactNavList) and the `.pane` card - the only place these items render at all
  // in this mode, so unlike compactNavList it always shows both the difficulty chip
  // and the solved checkmark, matching the grid card's own information.
  function richRow(item: TreeItem, pattern: string | null) {
    const solved = solvedIds?.has(item.id) ?? false
    return (
      <button
        type="button"
        key={item.id}
        className="fs-prow"
        aria-current={selectedId === item.id}
        onClick={() => onSelect(item, { domain: item.domain, pattern })}
      >
        <span className={`solved-check ${solved ? 'done' : 'pending'}`} aria-hidden="true">
          {solved ? '✓' : ''}
        </span>
        <span className="nm">{item.title}</span>
        <span className={`chipx ${difficultyTone(item.difficulty)}`}>
          {defaultDomainLabel(item.difficulty.toLowerCase())}
        </span>
      </button>
    )
  }

  if (sidebarMode) {
    return (
      <div>
        {findAndFilters}
        {searching ? (
          <div className="fs-item-list search-results">
            {searchResults.length === 0 && <p className="note">Nothing matches those filters.</p>}
            {searchResults.map((item) => richRow(item, null))}
          </div>
        ) : (
          renderDomainTree(
            (pattern, patternOpen) =>
              patternOpen ? (
                <div className="fs-item-list">
                  {pattern.items.map((item) => richRow(item, pattern.topic))}
                </div>
              ) : null,
            (group) =>
              openPattern === null ? (
                <div className="fs-item-list">
                  {group.items.map((item) => richRow(item, null))}
                </div>
              ) : null,
          )
        )}
      </div>
    )
  }

  return (
    <div className="browse">
      <div className="card tree">
        {findAndFilters}
        {/* The nav-rail's own selectable list (see `compactNavList`'s doc above) -
            scoped to this one expanded pattern's items only, matching `.pane`'s own
            activePattern.items scoping. */}
        {renderDomainTree((pattern, patternOpen) =>
          compactNavList && patternOpen ? (
            <div className="nav-item-list">
              {pattern.items.map((item) => (
                <button
                  type="button"
                  key={item.id}
                  className="nav-item"
                  aria-current={selectedId === item.id}
                  onClick={() => onSelect(item, { domain: item.domain, pattern: pattern.topic })}
                >
                  {item.title}
                </button>
              ))}
            </div>
          ) : null,
        )}
      </div>

      <div className="pane">
        <div className="crumb">
          <span>{sectionLabel}</span>
          {!searching && current && (
            <>
              <span aria-hidden="true">›</span>
              <b>{domainLabel(current.domain)}</b>
            </>
          )}
          {!searching && activePattern && (
            <>
              <span aria-hidden="true">›</span>
              <b>{topicLabel(activePattern.topic)}</b>
            </>
          )}
          <span aria-hidden="true">›</span>
          <span>
            {paneItems.length} {itemNoun}
            {paneItems.length === 1 ? '' : 's'}
          </span>
        </div>

        <div className="grid">
          {paneItems.length === 0 && <p className="note">Nothing matches those filters.</p>}
          {paneItems.map((item) => (
            <button
              type="button"
              key={item.id}
              className="item"
              aria-current={selectedId === item.id}
              onClick={() =>
                onSelect(item, {
                  domain: item.domain,
                  pattern: searching ? null : (activePattern?.topic ?? null),
                })
              }
            >
              <span className="it">{item.title}</span>
              <span className="im">
                <span className={`chipx ${difficultyTone(item.difficulty)}`}>
                  {defaultDomainLabel(item.difficulty.toLowerCase())}
                </span>
                {solvedIds?.has(item.id) && <span className="chipx g">Solved</span>}
                <span className="note">{item.topics.slice(0, 2).map(topicLabel).join(' · ')}</span>
              </span>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

export default TreeBrowser
