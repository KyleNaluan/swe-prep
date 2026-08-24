import { useEffect, useMemo, useRef, useState } from 'react'
import { apiFetch, errorMessage } from './api'
import TreeBrowser, { type FilterGroup } from './TreeBrowser'
import { familyLabel } from './familyLabels'
import { APP_NAME } from './appName'
import LessonBody, { type LessonBlockData } from './LessonBody'

// The lesson reading surface (issue #46/#41). A lesson is read, never attempted: there is no
// Run, no verdict, no attempt recorded. What turns reading from the lowest-utility study
// activity into a generative one is the embedded self-explanation prompts - each asks you to
// explain or predict something in your own words, then reveals a model answer to compare
// against. The reveal is entirely client-side and ungraded; nothing about a lesson feeds any
// score or the objective competence signal.

type LessonSummary = {
  id: string
  title: string
  domain: string
  difficulty: string
  promptCount: number
  topics?: string[]
  family?: string[]
}

// The captain's binding refinement: "make sure the learn has the difficulty filters
// too", in its own labeled group, separate from family - the same faceted shape
// Practice uses (issue #90).
const DIFFICULTY_FILTER_GROUP: FilterGroup = {
  key: 'difficulty',
  label: 'Difficulty',
  options: [
    { value: 'EASY', label: 'Easy' },
    { value: 'MEDIUM', label: 'Medium' },
    { value: 'HARD', label: 'Hard' },
  ],
}
const ALL_DIFFICULTIES = new Set(DIFFICULTY_FILTER_GROUP.options.map((o) => o.value))

type Prompt = { prompt: string; modelAnswer: string }

type LessonDetail = {
  id: string
  title: string
  statement: string
  domain: string
  difficulty: string
  body: LessonBlockData[]
  prompts: Prompt[]
}

function Lesson() {
  const [catalog, setCatalog] = useState<LessonSummary[] | null>(null)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [lesson, setLesson] = useState<LessonDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  // Same two labeled filter groups as Practice (issue #90/captain's refinement):
  // difficulty and family, each on by default, neither ever resetting which tree node
  // is open - that state lives inside TreeBrowser itself, untouched by these toggles.
  const [difficultyFilter, setDifficultyFilter] = useState<Set<string>>(
    () => new Set(ALL_DIFFICULTIES),
  )
  const [familyFilter, setFamilyFilter] = useState<Set<string>>(() => new Set())
  const familyFilterSeeded = useRef(false)
  // Scrolled into view on every TreeBrowser selection (issue #90) - the concept grid
  // above it can be long, so without this a freshly picked lesson could land far
  // below the fold, behind the very grid it was picked from.
  const lessonSectionRef = useRef<HTMLDivElement | null>(null)

  // Load the list of lessons once, and select the first.
  useEffect(() => {
    let cancelled = false
    apiFetch('/api/lessons')
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as LessonSummary[]
      })
      .then((loaded) => {
        if (cancelled) return
        setCatalog(loaded)
        if (!familyFilterSeeded.current) {
          familyFilterSeeded.current = true
          const seen = new Set<string>()
          loaded.forEach((summary) => summary.family?.forEach((f) => seen.add(f)))
          setFamilyFilter(seen)
        }
        if (loaded.length > 0) setSelectedId(loaded[0].id)
      })
      .catch((error: unknown) => {
        if (!cancelled) setCatalogError(error instanceof Error ? error.message : String(error))
      })
    return () => {
      cancelled = true
    }
  }, [])

  // Load the selected lesson whenever the selection changes.
  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    setLesson(null)
    setLoadError(null)
    apiFetch(`/api/lessons/${selectedId}`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as LessonDetail
      })
      .then((loaded) => {
        if (cancelled) return
        // A legacy lesson (no `body` blocks authored yet - every real lesson today) sends
        // `body` as an empty array; guard with `?? []` anyway so an older cached response
        // (or a hand-rolled fixture) with no `body` field at all still renders instead of
        // throwing on `.length`.
        setLesson({ ...loaded, body: loaded.body ?? [], prompts: loaded.prompts ?? [] })
        // Reading a lesson seeds its checks into the warm-up (issue #40): an inactive-family
        // lesson pulls that one concept's checks into review, one concept at a time. This is a
        // best-effort side effect of opening the lesson - a failure here must never break reading.
        void apiFetch(`/api/lessons/${loaded.id}/read`, { method: 'POST' }).catch(() => {})
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error))
      })
    return () => {
      cancelled = true
    }
  }, [selectedId])

  // The family filter group is built from whatever family tags the loaded catalog
  // actually carries - an untagged content set simply shows no family group, rather
  // than a row of always-inactive chips.
  const familyOptions = useMemo(() => {
    const seen = new Set<string>()
    catalog?.forEach((summary) => summary.family?.forEach((f) => seen.add(f)))
    return [...seen].sort()
  }, [catalog])

  const filterGroups: FilterGroup[] = useMemo(() => {
    const groups = [DIFFICULTY_FILTER_GROUP]
    if (familyOptions.length > 0) {
      groups.push({
        key: 'family',
        label: 'Family',
        options: familyOptions.map((f) => ({ value: f, label: familyLabel(f) })),
      })
    }
    return groups
  }, [familyOptions])

  const activeFilters = useMemo(
    () => ({ difficulty: difficultyFilter, family: familyFilter }),
    [difficultyFilter, familyFilter],
  )

  const onToggleFilter = (groupKey: string, value: string) => {
    const setter = groupKey === 'family' ? setFamilyFilter : setDifficultyFilter
    setter((prev) => {
      const next = new Set(prev)
      if (next.has(value)) next.delete(value)
      else next.add(value)
      return next
    })
  }

  if (catalogError) {
    return (
      <>
        <h1>{APP_NAME}</h1>
        <p className="status down">Could not load lessons: {catalogError}</p>
      </>
    )
  }
  if (!catalog) {
    return (
      <>
        <h1>{APP_NAME}</h1>
        <p className="status loading">Loading lessons...</p>
      </>
    )
  }
  if (catalog.length === 0) {
    return (
      <section className="warmup-done">
        <h1>No lessons yet</h1>
        <p className="hints-note">
          Lessons are taught content loaded from the content set. Once it is present they appear
          here to read.
        </p>
      </section>
    )
  }

  return (
    <>
      <div className="browsehead">
        <div>
          <h1>Learn</h1>
          <p>
            Reading, made generative: each lesson asks you to explain or predict in your own
            words before revealing the answer. Nothing here is graded.
          </p>
        </div>
      </div>

      <TreeBrowser
        items={catalog.map((summary) => ({
          id: summary.id,
          title: summary.title,
          domain: summary.domain,
          difficulty: summary.difficulty,
          topics: summary.topics ?? [],
          family: summary.family ?? [],
        }))}
        filterGroups={filterGroups}
        activeFilters={activeFilters}
        onToggleFilter={onToggleFilter}
        selectedId={selectedId}
        onSelect={(item) => {
          setSelectedId(item.id)
          lessonSectionRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
        }}
        findLabel="Find a concept"
        findPlaceholder="attention, indexing, CORS…"
        emptyMessage="No lessons available."
        sectionLabel="Learn"
        itemNoun="concept"
      />

      <div ref={lessonSectionRef}>
      {loadError && <p className="status down">Could not load the lesson: {loadError}</p>}
      {!lesson && !loadError && <p className="status loading">Loading lesson...</p>}

      {lesson && (
        <div className="card exl lesson-card">
          <header>
            <h1>{lesson.title}</h1>
            <span className="language-tag chipx">{lesson.domain}</span>
          </header>
          {lesson.body.length > 0 ? (
            <LessonBody blocks={lesson.body} />
          ) : (
            // Legacy fallback: a lesson still authored only as plain-text `statement`
            // (every real lesson today) - unstructured, but never a hard break.
            <p className="lesson-statement">{lesson.statement}</p>
          )}

          {lesson.prompts.length > 0 && (
            <section className="prompts">
              <h2>Explain as you read</h2>
              {lesson.prompts.map((prompt, index) => (
                <SelfExplainPrompt key={index} prompt={prompt} />
              ))}
            </section>
          )}
        </div>
      )}
      </div>
    </>
  )
}

// One ungraded self-explanation prompt: think about it, then reveal the model answer. The
// reveal is client-side only - a lesson is read, so nothing is recorded or graded.
function SelfExplainPrompt({ prompt }: { prompt: Prompt }) {
  const [revealed, setRevealed] = useState(false)
  return (
    <div className="prompt">
      <p className="prompt-question">{prompt.prompt}</p>
      {revealed ? (
        <p className="explanation-body">{prompt.modelAnswer}</p>
      ) : (
        <button type="button" className="secondary" onClick={() => setRevealed(true)}>
          Reveal the answer
        </button>
      )}
    </div>
  )
}

export default Lesson
