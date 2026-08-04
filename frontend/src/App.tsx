import { useEffect, useState } from 'react'
import './App.css'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080'

type HealthState =
  | { phase: 'loading' }
  | { phase: 'up'; status: string }
  | { phase: 'down'; message: string }

function useBackendHealth(): HealthState {
  const [state, setState] = useState<HealthState>({ phase: 'loading' })

  useEffect(() => {
    let cancelled = false

    fetch(`${API_BASE_URL}/actuator/health`)
      .then(async (response) => {
        const body = await response.json()
        if (cancelled) return
        if (response.ok && body.status === 'UP') {
          setState({ phase: 'up', status: body.status })
        } else {
          setState({ phase: 'down', message: `backend reported ${body.status}` })
        }
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setState({ phase: 'down', message: String(error) })
      })

    return () => {
      cancelled = true
    }
  }, [])

  return state
}

function App() {
  const health = useBackendHealth()

  return (
    <main>
      <h1>swe-prep</h1>
      <p>Backend health check</p>
      {health.phase === 'loading' && <span className="status loading">checking...</span>}
      {health.phase === 'up' && <span className="status up">backend is {health.status}</span>}
      {health.phase === 'down' && <span className="status down">backend is unreachable: {health.message}</span>}
    </main>
  )
}

export default App
