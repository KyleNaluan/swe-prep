import { useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'

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
}

type Prompt = { prompt: string; modelAnswer: string }

type LessonDetail = {
  id: string
  title: string
  statement: string
  domain: string
  difficulty: string
  prompts: Prompt[]
}

function Lesson() {
  const [catalog, setCatalog] = useState<LessonSummary[] | null>(null)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [lesson, setLesson] = useState<LessonDetail | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

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
        if (!cancelled) setLesson({ ...loaded, prompts: loaded.prompts ?? [] })
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error))
      })
    return () => {
      cancelled = true
    }
  }, [selectedId])

  if (catalogError) {
    return <p className="status down">Could not load lessons: {catalogError}</p>
  }
  if (!catalog) {
    return <p className="status loading">Loading lessons...</p>
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
      <p className="continuation-note">
        Reading, made generative: each lesson asks you to explain or predict in your own words
        before revealing the answer. Nothing here is graded.
      </p>

      <div className="picker">
        <label htmlFor="lesson-select">Lesson</label>
        <select
          id="lesson-select"
          value={selectedId ?? ''}
          onChange={(event) => setSelectedId(event.target.value)}
        >
          {catalog.map((summary) => (
            <option key={summary.id} value={summary.id}>
              {summary.title} · {summary.domain} · {summary.difficulty}
            </option>
          ))}
        </select>
      </div>

      {loadError && <p className="status down">Could not load the lesson: {loadError}</p>}
      {!lesson && !loadError && <p className="status loading">Loading lesson...</p>}

      {lesson && (
        <>
          <header>
            <h1>{lesson.title}</h1>
            <span className="language-tag">{lesson.domain}</span>
          </header>
          <p className="statement">{lesson.statement}</p>

          {lesson.prompts.length > 0 && (
            <section className="prompts">
              <h2>Explain as you read</h2>
              {lesson.prompts.map((prompt, index) => (
                <SelfExplainPrompt key={index} prompt={prompt} />
              ))}
            </section>
          )}
        </>
      )}
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
