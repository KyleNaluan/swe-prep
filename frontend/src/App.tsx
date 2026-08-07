import { useCallback, useEffect, useRef, useState } from 'react'
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

// An attempt as the history list reads it (the backend's AttemptView).
type AttemptView = {
  id: string
  exerciseId: string
  exerciseTitle: string
  domain: string
  form: string
  outcome: 'IN_PROGRESS' | 'SOLVED' | 'ABANDONED' | 'READ'
  startedAt: string
  endedAt: string | null
  submissionCount: number
  hintsTaken: number
  failingCaseRevealed: boolean
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
  const [solved, setSolved] = useState(false)

  const [history, setHistory] = useState<AttemptView[]>([])

  const codeRef = useRef<string>('')
  const [choice, setChoice] = useState<string | null>(null)

  // The active sitting for the selected exercise. Held in a ref so the selection
  // effect's cleanup can abandon it on switch-away without re-subscribing.
  const attemptRef = useRef<{ id: string; solved: boolean } | null>(null)

  const refreshHistory = useCallback(() => {
    fetch(`${API_BASE_URL}/api/attempts`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as AttemptView[]
      })
      .then(setHistory)
      .catch(() => {
        // History is a secondary panel; a failure here should not blank the editor.
      })
  }, [])

  // Abandon the active sitting if it was neither solved nor already ended. Used both
  // when switching exercises and when the user explicitly gives up.
  const abandonActive = useCallback(async () => {
    const active = attemptRef.current
    attemptRef.current = null
    if (!active || active.solved) return
    try {
      await fetch(`${API_BASE_URL}/api/attempts/${active.id}/abandon`, { method: 'POST' })
    } catch {
      // best effort; the record simply stays in progress if the call fails
    }
  }, [])

  // Load the list of exercises once, and select the first. Also load any history.
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
    refreshHistory()
    return () => {
      cancelled = true
    }
  }, [refreshHistory])

  // Load the selected exercise whenever the selection changes, and abandon the
  // sitting we are leaving if it was never solved.
  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    setExercise(null)
    setLoadError(null)
    setRun({ phase: 'idle' })
    setChoice(null)
    setSolved(false)
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
      // Leaving an unsolved sitting records it as abandoned, then refresh history.
      void abandonActive().then(refreshHistory)
    }
  }, [selectedId, abandonActive, refreshHistory])

  // Ensure a sitting is open for the current exercise, starting one lazily on the
  // first Run so glancing at an exercise never creates an empty attempt.
  async function ensureAttempt(exerciseId: string): Promise<string> {
    if (attemptRef.current) return attemptRef.current.id
    const response = await fetch(`${API_BASE_URL}/api/attempts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ exerciseId }),
    })
    if (!response.ok) throw new Error(await errorMessage(response))
    const attempt = (await response.json()) as AttemptView
    attemptRef.current = { id: attempt.id, solved: false }
    return attempt.id
  }

  async function handleSubmit() {
    if (!exercise) return
    const submission = exercise.response.kind === 'code' ? codeRef.current : (choice ?? '')
    setRun({ phase: 'running' })
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await fetch(`${API_BASE_URL}/api/attempts/${attemptId}/submissions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ submission }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const verdict = (await response.json()) as Verdict
      if (verdict.outcome === 'PASSED') {
        if (attemptRef.current) attemptRef.current.solved = true
        setSolved(true)
      }
      setRun({ phase: 'done', verdict })
      refreshHistory()
    } catch (error: unknown) {
      setRun({ phase: 'error', message: error instanceof Error ? error.message : String(error) })
    }
  }

  async function handleGiveUp() {
    await abandonActive()
    setSolved(false)
    setRun({ phase: 'idle' })
    refreshHistory()
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
          disabled={run.phase === 'running'}
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
                solved ||
                (exercise.response.kind === 'choice' && choice === null)
              }
            >
              {run.phase === 'running'
                ? 'Checking...'
                : exercise.response.kind === 'code'
                  ? 'Run'
                  : 'Submit'}
            </button>
            <button
              type="button"
              className="secondary"
              onClick={handleGiveUp}
              disabled={run.phase === 'running' || solved || attemptRef.current === null}
            >
              Give up
            </button>
          </div>

          <VerdictView run={run} scored={exercise.response.kind === 'choice'} />
        </>
      )}

      <History attempts={history} />
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

// A plain list of past sittings - the visible-history half of issue #15. It is
// deliberately minimal; the schedulers (issue #8) read the record, not this view.
function History({ attempts }: { attempts: AttemptView[] }) {
  if (attempts.length === 0) return null
  return (
    <section className="history">
      <h2>History</h2>
      <table>
        <thead>
          <tr>
            <th>Exercise</th>
            <th>Outcome</th>
            <th>Submissions</th>
            <th>Revealed</th>
            <th>Started</th>
          </tr>
        </thead>
        <tbody>
          {attempts.map((attempt) => (
            <tr key={attempt.id}>
              <td>{attempt.exerciseTitle}</td>
              <td>
                <span className={`outcome ${attempt.outcome.toLowerCase()}`}>
                  {attempt.outcome.replace('_', ' ').toLowerCase()}
                </span>
              </td>
              <td>{attempt.submissionCount}</td>
              <td>{attempt.failingCaseRevealed ? 'yes' : '-'}</td>
              <td>{new Date(attempt.startedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

export default App
