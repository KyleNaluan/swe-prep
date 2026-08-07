import { useEffect, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import './App.css'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type Summary = {
  id: string
  title: string
  domain: string
  difficulty: string
  form: string
}

type ResponseSpec =
  | { kind: 'code'; language: string; stub: string }
  | { kind: 'choice'; options: string[] }

type Exercise = {
  id: string
  title: string
  statement: string
  domain: string
  difficulty: string
  form: string
  response: ResponseSpec
}

type Verdict = {
  outcome: 'PASSED' | 'FAILED' | 'COMPILE_ERROR' | 'TIMEOUT' | 'ERROR'
  passed: number
  total: number
  detail: string
}

type RunState =
  | { phase: 'idle' }
  | { phase: 'running' }
  | { phase: 'done'; verdict: Verdict }
  | { phase: 'error'; message: string }

// The content endpoints answer a failure with a { error } body (see the backend's
// ContentErrorHandler); surface that message rather than a bare status code.
async function errorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { error?: string }
    if (body && typeof body.error === 'string') return body.error
  } catch {
    // fall through to the status
  }
  return `backend returned ${response.status}`
}

function App() {
  const [catalog, setCatalog] = useState<Summary[] | null>(null)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const [exercise, setExercise] = useState<Exercise | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [run, setRun] = useState<RunState>({ phase: 'idle' })

  const codeRef = useRef<string>('')
  const [choice, setChoice] = useState<string | null>(null)

  // Load the list of exercises once, and select the first.
  useEffect(() => {
    let cancelled = false
    fetch(`${API_BASE_URL}/api/exercises`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as Summary[]
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

  // Load the selected exercise whenever the selection changes.
  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    setExercise(null)
    setLoadError(null)
    setRun({ phase: 'idle' })
    setChoice(null)
    fetch(`${API_BASE_URL}/api/exercises/${selectedId}`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as Exercise
      })
      .then((loaded) => {
        if (cancelled) return
        setExercise(loaded)
        codeRef.current = loaded.response.kind === 'code' ? loaded.response.stub : ''
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error))
      })
    return () => {
      cancelled = true
    }
  }, [selectedId])

  async function handleSubmit() {
    if (!exercise) return
    const submission = exercise.response.kind === 'code' ? codeRef.current : (choice ?? '')
    setRun({ phase: 'running' })
    try {
      const response = await fetch(`${API_BASE_URL}/api/exercises/${exercise.id}/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ submission }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const verdict = (await response.json()) as Verdict
      setRun({ phase: 'done', verdict })
    } catch (error: unknown) {
      setRun({ phase: 'error', message: error instanceof Error ? error.message : String(error) })
    }
  }

  if (catalogError) {
    return (
      <main className="workspace">
        <h1>swe-prep</h1>
        <p className="status down">Could not load exercises: {catalogError}</p>
      </main>
    )
  }

  if (!catalog) {
    return (
      <main className="workspace">
        <h1>swe-prep</h1>
        <p className="status loading">Loading exercises...</p>
      </main>
    )
  }

  return (
    <main className="workspace">
      <div className="picker">
        <label htmlFor="exercise-select">Exercise</label>
        <select
          id="exercise-select"
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

      {loadError && <p className="status down">Could not load the exercise: {loadError}</p>}
      {!exercise && !loadError && <p className="status loading">Loading exercise...</p>}

      {exercise && (
        <>
          <header>
            <h1>{exercise.title}</h1>
            <span className="language-tag">
              {exercise.response.kind === 'code' ? exercise.response.language : exercise.domain}
            </span>
          </header>
          <p className="statement">{exercise.statement}</p>

          {exercise.response.kind === 'code' ? (
            <div className="editor">
              <Editor
                key={exercise.id}
                height="360px"
                defaultLanguage={exercise.response.language}
                defaultValue={exercise.response.stub}
                onChange={(value) => {
                  codeRef.current = value ?? ''
                }}
                options={{ minimap: { enabled: false }, fontSize: 14 }}
              />
            </div>
          ) : (
            <fieldset className="choices">
              <legend>Choose one</legend>
              {exercise.response.options.map((option) => (
                <label key={option} className="choice">
                  <input
                    type="radio"
                    name="answer"
                    value={option}
                    checked={choice === option}
                    onChange={() => setChoice(option)}
                  />
                  <span>{option}</span>
                </label>
              ))}
            </fieldset>
          )}

          <div className="actions">
            <button
              type="button"
              onClick={handleSubmit}
              disabled={
                run.phase === 'running' ||
                (exercise.response.kind === 'choice' && choice === null)
              }
            >
              {run.phase === 'running'
                ? 'Checking...'
                : exercise.response.kind === 'code'
                  ? 'Run'
                  : 'Submit'}
            </button>
          </div>

          <VerdictView run={run} scored={exercise.response.kind === 'choice'} />
        </>
      )}
    </main>
  )
}

function VerdictView({ run, scored }: { run: RunState; scored: boolean }) {
  if (run.phase === 'idle') return null
  if (run.phase === 'running') {
    return <p className="status loading">Checking your answer...</p>
  }
  if (run.phase === 'error') {
    return <p className="status down">Could not run: {run.message}</p>
  }

  const { verdict } = run
  switch (verdict.outcome) {
    case 'PASSED':
    case 'FAILED': {
      const label = scored
        ? verdict.outcome === 'PASSED'
          ? 'Correct'
          : 'Incorrect'
        : `${verdict.passed} of ${verdict.total} tests passed`
      return <p className={`status ${verdict.outcome === 'PASSED' ? 'up' : 'down'}`}>{label}</p>
    }
    case 'COMPILE_ERROR':
      return (
        <div className="verdict-block down">
          <p className="status down">Compile error</p>
          <pre className="detail">{verdict.detail}</pre>
        </div>
      )
    case 'TIMEOUT':
      return <p className="status down">{verdict.detail || 'Execution timed out'}</p>
    case 'ERROR':
      return (
        <div className="verdict-block down">
          <p className="status down">Could not run your solution</p>
          <pre className="detail">{verdict.detail}</pre>
        </div>
      )
  }
}

export default App
