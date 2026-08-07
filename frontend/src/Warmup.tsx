import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch, errorMessage } from './api'

// The warm-up runner: the ~8-rep, ~4-minute daily core (issues #3, #9, #18). It fetches
// the interleaved, family-filtered, gated set the backend builds, then walks it one rep
// at a time, driving the same attempt/submission flow every exercise uses (issue #15) -
// a rep is never a parallel system. The feedback loop is the point: a wrong answer shows
// the check's explanation at once and re-queues that rep later in the same set, while a
// correct answer withholds the explanation but keeps it one keystroke away, recording
// the ask (issue #51).

// A rep whose answer is wrong is re-queued at the end of the set, but only so many times,
// so a rep the solver keeps missing cannot loop the set forever.
const MAX_REQUEUES = 2

type RepSummary = {
  id: string
  title: string
  domain: string
  difficulty: string
  form: string
}

type ResponseSpec =
  | { kind: 'choice'; options: string[] }
  | { kind: 'freeText' }
  | { kind: 'code'; language: string; stub: string }

type Rep = {
  id: string
  title: string
  statement: string
  domain: string
  difficulty: string
  response: ResponseSpec
  hasExplanation: boolean
}

type Verdict = {
  outcome: 'PASSED' | 'FAILED' | 'COMPILE_ERROR' | 'TIMEOUT' | 'ERROR'
  passed: number
  total: number
  detail: string
  runtimeMillis?: number
  // On a wrong answer the check's explanation is disclosed automatically (issue #51).
  explanation?: string
}

type ExplanationResponse = { explanation?: string }

// The outcome of answering the current rep: correct or wrong, plus any explanation to
// show (disclosed automatically when wrong, fetched on request when correct).
type Answered = { correct: boolean; explanation: string | null }

type Phase =
  | { name: 'loading' }
  | { name: 'error'; message: string }
  | { name: 'empty' }
  | { name: 'active' }
  | { name: 'done' }

// When embedded in the daily session loop the parent is told the moment the set is
// finished, so it can complete the day and show the day-complete landing (issue #19).
// The prop is optional so the runner still works standalone (it then shows its own
// built-in done screen).
type WarmupProps = { onComplete?: (correctCount: number) => void }

function Warmup({ onComplete }: WarmupProps = {}) {
  const [phase, setPhase] = useState<Phase>({ name: 'loading' })
  // The working queue of rep ids. A wrong answer appends its rep, so the queue can grow
  // past the original set; `pos` walks it and the set ends when `pos` runs off the end.
  const [queue, setQueue] = useState<string[]>([])
  const [pos, setPos] = useState(0)
  const [correctCount, setCorrectCount] = useState(0)

  const [rep, setRep] = useState<Rep | null>(null)
  const [repError, setRepError] = useState<string | null>(null)
  const [submitError, setSubmitError] = useState<string | null>(null)
  const [choice, setChoice] = useState<string | null>(null)
  const [text, setText] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [answered, setAnswered] = useState<Answered | null>(null)
  const [explanationBusy, setExplanationBusy] = useState(false)

  // How many times each rep has been re-queued, so a persistently wrong rep is bounded.
  const requeues = useRef<Record<string, number>>({})
  // The current sitting: its id and whether it has been solved. Held in a ref so leaving
  // an unsolved rep can abandon it without re-subscribing effects (mirrors the editor).
  const attemptRef = useRef<{ id: string; solved: boolean } | null>(null)

  const abandonIfUnsolved = useCallback(async () => {
    const active = attemptRef.current
    attemptRef.current = null
    if (!active || active.solved) return
    try {
      await apiFetch(`/api/attempts/${active.id}/abandon`, { method: 'POST' })
    } catch {
      // best effort; the record simply stays in progress if the call fails
    }
  }, [])

  // Leaving the warm-up (tab switch or unmount) abandons an unsolved sitting, so it never
  // lingers IN_PROGRESS - the same switch-away invariant the editor honours (App.tsx).
  useEffect(() => {
    return () => {
      void abandonIfUnsolved()
    }
  }, [abandonIfUnsolved])

  // Load the warm-up set once.
  useEffect(() => {
    let cancelled = false
    apiFetch(`/api/reps/warmup`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as RepSummary[]
      })
      .then((set) => {
        if (cancelled) return
        if (set.length === 0) {
          setPhase({ name: 'empty' })
          return
        }
        setQueue(set.map((s) => s.id))
        setPos(0)
        setPhase({ name: 'active' })
      })
      .catch((error: unknown) => {
        if (!cancelled) setPhase({ name: 'error', message: message(error) })
      })
    return () => {
      cancelled = true
    }
  }, [])

  const currentId = phase.name === 'active' ? queue[pos] : undefined

  // Load the rep at the current position. Keyed on the position and its id, not the whole
  // queue, so re-queueing a wrong rep (which appends to the queue) does not reload and
  // wipe the feedback the solver is reading - only moving to a new position reloads.
  useEffect(() => {
    if (!currentId) return
    let cancelled = false
    setRep(null)
    setRepError(null)
    setSubmitError(null)
    setChoice(null)
    setText('')
    setAnswered(null)
    attemptRef.current = null
    apiFetch(`/api/exercises/${currentId}`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as Rep
      })
      .then((loaded) => {
        if (!cancelled) setRep({ ...loaded, hasExplanation: loaded.hasExplanation ?? false })
      })
      .catch((error: unknown) => {
        if (!cancelled) setRepError(message(error))
      })
    return () => {
      cancelled = true
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [currentId, pos])

  async function ensureAttempt(exerciseId: string): Promise<string> {
    if (attemptRef.current) return attemptRef.current.id
    const response = await apiFetch(`/api/attempts`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ exerciseId }),
    })
    if (!response.ok) throw new Error(await errorMessage(response))
    const attempt = (await response.json()) as { id: string }
    attemptRef.current = { id: attempt.id, solved: false }
    return attempt.id
  }

  async function handleSubmit() {
    if (!rep) return
    const submission = rep.response.kind === 'choice' ? (choice ?? '') : text
    setSubmitting(true)
    setSubmitError(null)
    try {
      const attemptId = await ensureAttempt(rep.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/submissions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ submission }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const verdict = (await response.json()) as Verdict
      const correct = verdict.outcome === 'PASSED'
      if (correct) {
        if (attemptRef.current) attemptRef.current.solved = true
        setCorrectCount((n) => n + 1)
      } else {
        requeue(rep.id)
      }
      // A wrong answer discloses the explanation immediately; a correct one withholds it,
      // one keystroke away below.
      setAnswered({ correct, explanation: correct ? null : (verdict.explanation ?? null) })
    } catch (error: unknown) {
      setSubmitError(message(error))
    } finally {
      setSubmitting(false)
    }
  }

  // Append the rep to the end of the set so it is seen again later, bounded by MAX_REQUEUES.
  function requeue(id: string) {
    const seen = requeues.current[id] ?? 0
    if (seen >= MAX_REQUEUES) return
    requeues.current[id] = seen + 1
    setQueue((q) => [...q, id])
  }

  // Ask why the correct answer is correct (issue #51). Honoured only from a terminal
  // attempt - the rep is solved, so this is the "when correct" path - and recorded as its
  // own confidence signal, never a penalty.
  async function handleRequestExplanation() {
    const active = attemptRef.current
    if (!active) return
    setExplanationBusy(true)
    try {
      const response = await apiFetch(`/api/attempts/${active.id}/explanation`, { method: 'POST' })
      if (!response.ok) throw new Error(await errorMessage(response))
      const disclosed = (await response.json()) as ExplanationResponse
      if (disclosed.explanation) {
        setAnswered((prev) => (prev ? { ...prev, explanation: disclosed.explanation ?? null } : prev))
      }
    } catch {
      // best-effort; a failure here should not disrupt the solved state
    } finally {
      setExplanationBusy(false)
    }
  }

  async function handleNext() {
    await abandonIfUnsolved()
    if (pos + 1 >= queue.length) {
      // The set is finished. In the session loop the parent owns what comes next (record
      // the day complete, show the landing); standalone, fall back to the built-in done
      // screen so the runner still works on its own.
      if (onComplete) {
        onComplete(correctCount)
      } else {
        setPhase({ name: 'done' })
      }
      return
    }
    setPos((p) => p + 1)
  }

  if (phase.name === 'loading') {
    return <p className="status loading">Loading your warm-up...</p>
  }
  if (phase.name === 'error') {
    return <p className="status down">Could not load your warm-up: {phase.message}</p>
  }
  if (phase.name === 'empty') {
    return (
      <section className="warmup-done">
        <h1>Nothing to warm up on yet</h1>
        <p className="hints-note">
          Attempt a few problems in Practice and their reps will start appearing here.
          Pattern-identification reps are available cold, so once content is loaded this set
          fills on its own.
        </p>
      </section>
    )
  }
  if (phase.name === 'done') {
    return (
      <section className="warmup-done">
        <h1>Warm-up complete</h1>
        <p className="status up">
          {correctCount} {correctCount === 1 ? 'rep' : 'reps'} answered correctly. That is your
          streak earned for today.
        </p>
      </section>
    )
  }

  return (
    <section className="warmup">
      <div className="warmup-progress">
        <span className="warmup-count">
          Rep {pos + 1} of {queue.length}
        </span>
      </div>

      {repError && (
        <div className="rep-load-error">
          <p className="status down">Could not load this rep: {repError}</p>
          <div className="actions">
            <button type="button" onClick={handleNext}>
              {pos + 1 >= queue.length ? 'Finish warm-up' : 'Skip rep'}
            </button>
          </div>
        </div>
      )}
      {!rep && !repError && <p className="status loading">Loading rep...</p>}

      {rep && (
        <>
          <header>
            <h1>{rep.title}</h1>
            <span className="language-tag">{rep.domain}</span>
          </header>
          <p className="statement">{rep.statement}</p>

          <RepAnswer
            response={rep.response}
            choice={choice}
            text={text}
            disabled={answered !== null || submitting}
            onChoose={setChoice}
            onType={setText}
          />

          {answered === null ? (
            <>
              {submitError && <p className="status down">Could not submit: {submitError}</p>}
              <div className="actions">
                <button
                  type="button"
                  onClick={handleSubmit}
                  disabled={
                    submitting ||
                    (rep.response.kind === 'choice' && choice === null) ||
                    (rep.response.kind === 'freeText' && text.trim() === '')
                  }
                >
                  {submitting ? 'Checking...' : 'Submit'}
                </button>
              </div>
            </>
          ) : (
            <RepFeedback
              answered={answered}
              hasExplanation={rep.hasExplanation}
              explanationBusy={explanationBusy}
              lastRep={pos + 1 >= queue.length}
              onRequestExplanation={handleRequestExplanation}
              onNext={handleNext}
            />
          )}
        </>
      )}
    </section>
  )
}

// The control the solver answers a rep with: radio options, or a free-text box for the
// "predict the output" rep (issue #18). A code response never reaches the warm-up (reps
// are recognition, not production), so it is handled only defensively.
function RepAnswer({
  response,
  choice,
  text,
  disabled,
  onChoose,
  onType,
}: {
  response: ResponseSpec
  choice: string | null
  text: string
  disabled: boolean
  onChoose: (value: string) => void
  onType: (value: string) => void
}) {
  if (response.kind === 'choice') {
    return (
      <fieldset className="choices" disabled={disabled}>
        <legend>Choose one</legend>
        {response.options.map((option) => (
          <label key={option} className="choice">
            <input
              type="radio"
              name="answer"
              value={option}
              checked={choice === option}
              onChange={() => onChoose(option)}
            />
            <span>{option}</span>
          </label>
        ))}
      </fieldset>
    )
  }
  if (response.kind === 'freeText') {
    return (
      <div className="freetext">
        <label htmlFor="predict-output">Type the exact output</label>
        <input
          id="predict-output"
          type="text"
          className="hypothesis"
          value={text}
          disabled={disabled}
          onChange={(event) => onType(event.target.value)}
        />
      </div>
    )
  }
  return <p className="status down">This rep type is not answerable in the warm-up.</p>
}

// What the solver sees after answering: correct or incorrect, the explanation (shown at
// once when wrong, one keystroke away when correct), and the button to the next rep.
function RepFeedback({
  answered,
  hasExplanation,
  explanationBusy,
  lastRep,
  onRequestExplanation,
  onNext,
}: {
  answered: Answered
  hasExplanation: boolean
  explanationBusy: boolean
  lastRep: boolean
  onRequestExplanation: () => void
  onNext: () => void
}) {
  return (
    <>
      <p className={`status ${answered.correct ? 'up' : 'down'}`}>
        {answered.correct ? 'Correct' : 'Incorrect'}
      </p>

      {answered.explanation && (
        <section className="explanation">
          <h2>Why this is the answer</h2>
          <p className="explanation-body">{answered.explanation}</p>
        </section>
      )}

      {answered.correct && hasExplanation && !answered.explanation && (
        <section className="explanation">
          <button
            type="button"
            className="secondary"
            onClick={onRequestExplanation}
            disabled={explanationBusy}
          >
            {explanationBusy ? 'Revealing...' : 'Why is this the answer?'}
          </button>
          <p className="hints-note">
            Asking is recorded, but it never affects your score - it is not a hint.
          </p>
        </section>
      )}

      {!answered.correct && (
        <p className="hints-note">You will see this rep again later in the set.</p>
      )}

      <div className="actions">
        <button type="button" onClick={onNext}>
          {lastRep ? 'Finish warm-up' : 'Next rep'}
        </button>
      </div>
    </>
  )
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

export default Warmup
