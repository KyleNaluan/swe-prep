import { useEffect, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import './App.css'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type Exercise = {
  id: string
  title: string
  statement: string
  language: string
  stub: string
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

function App() {
  const [exercise, setExercise] = useState<Exercise | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [run, setRun] = useState<RunState>({ phase: 'idle' })
  const codeRef = useRef<string>('')

  useEffect(() => {
    let cancelled = false
    fetch(`${API_BASE_URL}/api/exercise`)
      .then(async (response) => {
        if (!response.ok) throw new Error(`backend returned ${response.status}`)
        return (await response.json()) as Exercise
      })
      .then((loaded) => {
        if (cancelled) return
        setExercise(loaded)
        codeRef.current = loaded.stub
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(String(error))
      })
    return () => {
      cancelled = true
    }
  }, [])

  async function handleRun() {
    setRun({ phase: 'running' })
    try {
      const response = await fetch(`${API_BASE_URL}/api/exercise/run`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: codeRef.current }),
      })
      if (!response.ok) throw new Error(`backend returned ${response.status}`)
      const verdict = (await response.json()) as Verdict
      setRun({ phase: 'done', verdict })
    } catch (error: unknown) {
      setRun({ phase: 'error', message: String(error) })
    }
  }

  if (loadError) {
    return (
      <main className="workspace">
        <h1>swe-prep</h1>
        <p className="status down">Could not load the exercise: {loadError}</p>
      </main>
    )
  }

  if (!exercise) {
    return (
      <main className="workspace">
        <h1>swe-prep</h1>
        <p className="status loading">Loading exercise...</p>
      </main>
    )
  }

  return (
    <main className="workspace">
      <header>
        <h1>{exercise.title}</h1>
        <span className="language-tag">{exercise.language}</span>
      </header>
      <p className="statement">{exercise.statement}</p>

      <div className="editor">
        <Editor
          height="360px"
          defaultLanguage={exercise.language}
          defaultValue={exercise.stub}
          onChange={(value) => {
            codeRef.current = value ?? ''
          }}
          options={{ minimap: { enabled: false }, fontSize: 14 }}
        />
      </div>

      <div className="actions">
        <button type="button" onClick={handleRun} disabled={run.phase === 'running'}>
          {run.phase === 'running' ? 'Running...' : 'Run'}
        </button>
      </div>

      <VerdictView run={run} />
    </main>
  )
}

function VerdictView({ run }: { run: RunState }) {
  if (run.phase === 'idle') return null
  if (run.phase === 'running') {
    return <p className="status loading">Compiling and running your solution...</p>
  }
  if (run.phase === 'error') {
    return <p className="status down">Could not run: {run.message}</p>
  }

  const { verdict } = run
  switch (verdict.outcome) {
    case 'PASSED':
    case 'FAILED':
      return (
        <p className={`status ${verdict.outcome === 'PASSED' ? 'up' : 'down'}`}>
          {verdict.passed} of {verdict.total} tests passed
        </p>
      )
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
