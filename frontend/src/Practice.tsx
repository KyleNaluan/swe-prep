import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Editor from '@monaco-editor/react'
import { apiFetch, errorMessage } from './api'
import TreeBrowser, { type FilterGroup } from './TreeBrowser'
import { familyLabel } from './familyLabels'
import { APP_NAME } from './appName'
import { usePrefersDark } from './usePrefersDark'

// The practice surface: the optional main exercise and the open continuation that follow
// the warm-up (issue #19, tiers 2 and 3). It is one uncapped browse-and-solve editor -
// the "main" is just the first thing offered here, and continuing past it is only picking
// another exercise, with no limit. Reached both from the day-complete landing ("start a
// main exercise") and directly via the Practice tab (browse is always available). Nothing
// here is required: the day was already completed by the warm-up.
//
// It reuses the whole attempt lifecycle (issue #15): a sitting starts lazily on the first
// Run, switching away abandons an unsolved one (so a part-finished main is recorded as
// abandoned, not lost), and the hint ladder, failing-case reveal and explanation all work
// exactly as they do everywhere else. It assumes nothing about the domain - a code problem
// and a multiple-choice concept render through the same flow.

type Summary = {
  id: string
  title: string
  domain: string
  difficulty: string
  form: string
  // Both optional on the wire type since older fixtures/tests predate them; TreeBrowser
  // treats an absent value as "untagged" rather than throwing.
  topics?: string[]
  family?: string[]
}

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

type ResponseSpec =
  | { kind: 'code'; language: string; stub: string }
  | { kind: 'choice'; options: string[] }
  // A machine-graded "predict the output" free-text box (issue #18).
  | { kind: 'freeText' }
  // A self-graded "explain in your own words" produce-then-reveal item (issue #41). The
  // model answer is never in this spec - it is disclosed only after the learner commits.
  | { kind: 'selfCheck' }
  // A SQL query against a shared fixture schema (issue #25). Shares `language`/`stub`
  // with `code` on purpose - the editor renders both the same Monaco box, just with a
  // different syntax mode - but is graded through the SQL runner/grader seam, never the
  // language one, and its verdict is worded in rows rather than tests.
  | { kind: 'query'; language: string; stub: string }

// A self-rating of a revealed self-check answer (issue #41). Never a score, never graded.
type SelfRating = 'NAILED_IT' | 'PARTIAL' | 'MISSED'

// What the self-check reveal returns: the model answer, and the committed submission to rate.
type SelfCheckReveal = { submissionId: string; modelAnswer: string }

type Exercise = {
  id: string
  title: string
  statement: string
  domain: string
  difficulty: string
  form: string
  response: ResponseSpec
  // Hint-ladder rung names, least revealing first. Bodies are never sent up front;
  // each is fetched only when the solver explicitly takes that rung (issue #16).
  hints: string[]
  // Whether this check carries an explanation of why the correct answer is correct
  // (issue #51). The text never travels up front - it is shown automatically on a wrong
  // answer or fetched on request when correct - so only its existence is known here.
  hasExplanation: boolean
  // Whether this exercise runs the complexity self-report flow (issue #17) - never
  // the authored target itself. The target is not knowable from here by construction:
  // it is disclosed only from POST .../complexity, after the claim is already
  // recorded, so it can never sit in a response already held while the claim prompt
  // renders (the real information-ordering guarantee the ticket asks for).
  hasComplexityCheck: boolean
}

// The coarse complexity vocabulary the self-report and target-reveal flow is written
// in (issue #17) - deliberately closed, not free text: the empirical check behind it
// can only ever discriminate a polynomial degree change, never a constant factor.
const COMPLEXITY_OPTIONS = [
  'CONSTANT',
  'LOGARITHMIC',
  'LINEAR',
  'LINEARITHMIC',
  'QUADRATIC',
  'CUBIC',
  'EXPONENTIAL',
] as const
type Complexity = (typeof COMPLEXITY_OPTIONS)[number]

const COMPLEXITY_LABELS: Record<Complexity, string> = {
  CONSTANT: 'O(1) - constant',
  LOGARITHMIC: 'O(log n) - logarithmic',
  LINEAR: 'O(n) - linear',
  LINEARITHMIC: 'O(n log n) - linearithmic',
  QUADRATIC: 'O(n²) - quadratic',
  CUBIC: 'O(n³) - cubic',
  EXPONENTIAL: 'O(2ⁿ) or worse - exponential',
}

// The response from POST .../complexity: the attempt with the claim and measurement
// recorded, the authored target - revealed here for the first time - and the coarse
// measurement status. Per the honesty constraint (issue #17), "CONSISTENT" is worded
// by the editor as "measured scaling is consistent with your claim", never "correct";
// "INCONCLUSIVE" is its own first-class outcome, never silently treated as a pass.
// One measured point on the Direction A graft's log-log plot: an input size and its
// runtime in milliseconds.
type MeasurementPoint = { size: number; millis: number }

type ComplexityResponse = {
  targetTime: Complexity
  targetSpace: Complexity
  status: 'CONSISTENT' | 'CONTRADICTED' | 'INCONCLUSIVE' | 'SKIPPED'
  detail?: string
  // Whether the LLM complexity second opinion (issue #83) can be requested from here.
  // False whenever no advisor is configured server-side - the whole mechanism behind
  // "missing API key means the feature is absent, not broken": there is simply no
  // button to show, never a button that fails when pressed.
  modelOpinionAvailable: boolean
  // Present only alongside a measured status (CONSISTENT/CONTRADICTED) - the fitted
  // log-log slope, its confidence half-width, and the (size, ms) points it was drawn
  // from (issue #90's graft from Direction A). Absent for SKIPPED/INCONCLUSIVE, since
  // there is either no measurement or not enough of one to draw a fit from.
  exponent?: number
  confidenceHalfWidth?: number
  points?: MeasurementPoint[]
}

// The response from POST .../complexity/model-opinion (issue #83): the model's own
// reading and reasoning, plus the three-way comparison against the claim and the
// empirical measurement. Advisory only - this is never a verdict, and it is never
// persisted. agreement true means every voice that was present agreed (nothing to
// show beyond quiet confirmation); false means disagreementPrompt carries a neutral
// question for the learner to resolve in their own words, never a statement of which
// voice is right.
type ModelOpinionResponse = {
  modelTime: Complexity
  modelReasoning: string
  agreement: boolean
  disagreementPrompt?: string
}

type Verdict = {
  outcome: 'PASSED' | 'FAILED' | 'COMPILE_ERROR' | 'TIMEOUT' | 'ERROR'
  passed: number
  total: number
  detail: string
  // Shown for interest only; never part of the verdict (issue #16/#5).
  runtimeMillis?: number
  // Present only on a wrong answer: the check's explanation, disclosed automatically
  // (issue #51). Withheld on a pass, where it is one keystroke away on request instead.
  explanation?: string
  // Whether this solve was just committed to the private content repo (issue #22) - an
  // honest confirmation the mechanic ran, not an invented reward. False/absent on
  // anything but a fresh solve of a coding challenge.
  solutionCommitted?: boolean
}

// One revealed hint rung: its name and the body disclosed when it was taken.
type RevealedHint = { name: string; body: string }

// The response from POST .../hints: the rung just disclosed (name/body absent when
// the ladder is exhausted) and how far up the ladder we are now.
type HintResponse = {
  rungsTaken: number
  totalRungs: number
  name?: string
  body?: string
}

// The failing case a reveal discloses: input, expected, and what the code produced.
type FailingCase = {
  input: unknown
  expected: unknown
  actual?: unknown
  note?: string
}

type RevealResponse = {
  failingCase?: FailingCase
}

// An attempt as the history list reads it (the backend's AttemptView).
type AttemptView = {
  id: string
  exerciseId: string
  exerciseTitle: string
  domain: string
  form: string
  outcome: 'IN_PROGRESS' | 'SOLVED' | 'ABANDONED' | 'READ' | 'EXPLAINED'
  startedAt: string
  endedAt: string | null
  submissionCount: number
  hintsTaken: number
  failingCaseRevealed: boolean
  // Whether the solver asked to see the check's explanation (issue #51) - a confidence
  // signal recorded distinctly from taking a hint, never a penalty.
  explanationRequested: boolean
  // Whether the reference solution was revealed on this attempt before it ever passed
  // (issue #82) - never a penalty, but it does mean this solve will not count toward
  // "solved cold" until a later, clean pass.
  solutionSeen: boolean
}

// The response from POST .../explanation: the check's explanation, absent when the check
// carries none (the request is still recorded).
type ExplanationResponse = {
  explanation?: string
}

// The response from POST .../solution (issue #82): the disclosed reference solution
// (absent when content has none authored), and whether this reveal happened before the
// attempt had passed - the distinction the pre-pass/post-pass presentation reads.
type SolutionResponse = {
  solution?: string
  prePass: boolean
}

// After this many failed submissions in a row, the app quietly offers the next hint.
// Withholding is only a desirable difficulty while retrieval eventually succeeds, so
// the nudge keeps a stuck solver from unproductive struggle - it is help, never a
// reprimand, and taking it costs nothing (issue #16, pedagogy audit).
const STUCK_NUDGE_AFTER_FAILURES = 2

type RunState =
  | { phase: 'idle' }
  | { phase: 'running' }
  | { phase: 'done'; verdict: Verdict }
  | { phase: 'error'; message: string }

// The main exercise is chosen by the backend's priority scheduler (issue #21) - it
// scores review debt, staleness and topic coverage, not simply "the first CHALLENGE in
// the list". Falls back to that old static pick only if the call fails, or the
// scheduler genuinely has nothing to offer (e.g. every challenge is within its minimum
// interval) - so the picker never comes up empty just because the network hiccuped.
async function pickMain(loaded: Summary[]): Promise<string> {
  try {
    const response = await apiFetch(`/api/challenges/next`)
    if (response.ok) {
      const picked = (await response.json()) as { exercise: Summary | null }
      if (picked.exercise) return picked.exercise.id
    }
  } catch {
    // fall through to the static fallback below
  }
  const fallback = loaded.find((summary) => summary.form === 'CHALLENGE') ?? loaded[0]
  return fallback.id
}

// `onSolved` fires whenever an exercise here is solved. The session uses it only as the
// fallback that completes the day when the warm-up set was empty (issue #19); normally
// the warm-up completes the day and the main exercise stays optional, so this is a no-op.
function Practice({
  dayComplete = false,
  onSolved,
}: {
  dayComplete?: boolean
  onSolved?: () => void
}) {
  // Monaco has its own theme, separate from the page's CSS (issue #90) - see
  // usePrefersDark's own doc for why this can't just be a stylesheet rule.
  const prefersDark = usePrefersDark()
  const [catalog, setCatalog] = useState<Summary[] | null>(null)
  const [catalogError, setCatalogError] = useState<string | null>(null)
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // The TreeBrowser's difficulty and family filters (issue #90), each its own labeled
  // group per the captain's refinement. Both default to "everything active" - nothing
  // is suppressed before a choice is made - and toggling either is a prop change into
  // TreeBrowser, never a reset of which tree node is open (the captain's other,
  // binding refinement: a filter re-filters in place).
  const [difficultyFilter, setDifficultyFilter] = useState<Set<string>>(
    () => new Set(ALL_DIFFICULTIES),
  )
  const [familyFilter, setFamilyFilter] = useState<Set<string>>(() => new Set())
  const familyFilterSeeded = useRef(false)

  // The language a code exercise is solved in (issue #26). Java is the default,
  // matching the backend's own default when none is sent; a SQL/choice/free-text
  // exercise ignores this entirely. `languages` is the picker's option list, fetched
  // once - a failure there just leaves Java as the only option, never blocking the page.
  const [language, setLanguage] = useState('java')
  const [languages, setLanguages] = useState<string[]>(['java'])

  const [exercise, setExercise] = useState<Exercise | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [run, setRun] = useState<RunState>({ phase: 'idle' })
  const [solved, setSolved] = useState(false)

  // Help the solver has chosen this sitting: the hint rungs taken (in order), the
  // disclosed failing case, and how many runs have failed in a row (for the nudge).
  const [revealedHints, setRevealedHints] = useState<RevealedHint[]>([])
  const [hintBusy, setHintBusy] = useState(false)
  const [failingCase, setFailingCase] = useState<FailingCase | null>(null)
  const [revealPrompting, setRevealPrompting] = useState(false)
  const [hypothesis, setHypothesis] = useState('')
  const [revealBusy, setRevealBusy] = useState(false)
  const [consecutiveFailures, setConsecutiveFailures] = useState(0)
  // The check's explanation, once disclosed (issue #51): shown automatically on a wrong
  // answer, or fetched on request when correct. Null until there is something to show.
  const [explanation, setExplanation] = useState<string | null>(null)
  const [explanationBusy, setExplanationBusy] = useState(false)

  // The reference-solution reveal (issue #82): available on request at any time,
  // recorded, never penalised. `solutionPrePass` distinguishes the two presentations -
  // seen before this attempt ever passed (marks it solution-seen) vs. freely afterward.
  const [solution, setSolution] = useState<string | null>(null)
  const [solutionPrePass, setSolutionPrePass] = useState(false)
  const [solutionBusy, setSolutionBusy] = useState(false)
  const [solutionError, setSolutionError] = useState<string | null>(null)

  // The complexity self-report flow (issue #17): the claim picked (before it is sent),
  // the result once claimed (the authored target and measurement, revealed only then),
  // and request state. Null result means "not claimed yet for this sitting".
  const [complexityTimeClaim, setComplexityTimeClaim] = useState<Complexity>('LINEAR')
  const [complexitySpaceClaim, setComplexitySpaceClaim] = useState<Complexity>('LINEAR')
  const [complexityResult, setComplexityResult] = useState<ComplexityResponse | null>(null)
  const [complexityBusy, setComplexityBusy] = useState(false)
  const [complexityError, setComplexityError] = useState<string | null>(null)

  // The LLM complexity second opinion (issue #83): on request only, after the claim
  // above is already recorded. Null result means "not requested yet for this sitting".
  const [modelOpinionResult, setModelOpinionResult] = useState<ModelOpinionResponse | null>(null)
  const [modelOpinionBusy, setModelOpinionBusy] = useState(false)
  const [modelOpinionError, setModelOpinionError] = useState<string | null>(null)

  const [history, setHistory] = useState<AttemptView[]>([])

  const codeRef = useRef<string>('')
  const [choice, setChoice] = useState<string | null>(null)
  // The free-text box's contents, shared by the machine-graded predict-output item and the
  // self-graded explain item (both render a text box; only their grading differs).
  const [text, setText] = useState('')

  // The self-check produce-then-reveal flow (issue #41): the revealed model answer + the
  // submission to rate (null until the learner commits their text), and the rating placed.
  const [reveal, setReveal] = useState<SelfCheckReveal | null>(null)
  const [selfRating, setSelfRating] = useState<SelfRating | null>(null)
  const [selfCheckBusy, setSelfCheckBusy] = useState(false)
  const [selfCheckError, setSelfCheckError] = useState<string | null>(null)

  // The active sitting for the selected exercise. Held in a ref so the selection
  // effect's cleanup can abandon it on switch-away without re-subscribing.
  const attemptRef = useRef<{ id: string; solved: boolean } | null>(null)
  // Scrolled into view on every TreeBrowser selection (issue #90) - the grid above it
  // can run to hundreds of cards, so without this a freshly picked exercise could
  // land far below the fold.
  const exerciseSectionRef = useRef<HTMLDivElement | null>(null)

  const refreshHistory = useCallback(() => {
    apiFetch(`/api/attempts`)
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
      await apiFetch(`/api/attempts/${active.id}/abandon`, { method: 'POST' })
    } catch {
      // best effort; the record simply stays in progress if the call fails
    }
  }, [])

  // Load the list of exercises once, and select a main. Also load any history.
  useEffect(() => {
    let cancelled = false
    apiFetch(`/api/exercises`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as Summary[]
      })
      .then(async (loaded) => {
        if (cancelled) return
        setCatalog(loaded)
        if (!familyFilterSeeded.current) {
          familyFilterSeeded.current = true
          const seen = new Set<string>()
          loaded.forEach((summary) => summary.family?.forEach((f) => seen.add(f)))
          setFamilyFilter(seen)
        }
        if (loaded.length === 0) return
        setSelectedId(await pickMain(loaded))
      })
      .catch((error: unknown) => {
        if (!cancelled) setCatalogError(error instanceof Error ? error.message : String(error))
      })
    apiFetch(`/api/languages`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as string[]
      })
      .then((loaded) => {
        if (!cancelled && loaded.length > 0) setLanguages(loaded)
      })
      .catch(() => {
        // Best-effort: the picker just stays at its Java-only default.
      })
    refreshHistory()
    return () => {
      cancelled = true
    }
  }, [refreshHistory])

  // Load the selected exercise whenever the selection - or the chosen language
  // (issue #26) - changes, and abandon the sitting we are leaving if it was never
  // solved. A language switch is treated as starting fresh on this exercise, the
  // same as switching exercises: the stub, and everything about the prior sitting,
  // is specific to the language just left.
  useEffect(() => {
    if (!selectedId) return
    let cancelled = false
    setExercise(null)
    setLoadError(null)
    setRun({ phase: 'idle' })
    setChoice(null)
    setText('')
    setReveal(null)
    setSelfRating(null)
    setSelfCheckError(null)
    setSolved(false)
    setRevealedHints([])
    setFailingCase(null)
    setRevealPrompting(false)
    setHypothesis('')
    setConsecutiveFailures(0)
    setExplanation(null)
    setComplexityResult(null)
    setComplexityError(null)
    setComplexityTimeClaim('LINEAR')
    setComplexitySpaceClaim('LINEAR')
    setSolution(null)
    setSolutionPrePass(false)
    setSolutionError(null)
    setModelOpinionResult(null)
    setModelOpinionError(null)
    apiFetch(`/api/exercises/${selectedId}?language=${encodeURIComponent(language)}`)
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as Exercise
      })
      .then((loaded) => {
        if (cancelled) return
        // Older payloads may omit the hint ladder, the explanation flag, or the
        // complexity-check flag; treat a missing ladder as empty and missing flags as
        // false so the rest of the UI can rely on all of them.
        setExercise({
          ...loaded,
          hints: loaded.hints ?? [],
          hasExplanation: loaded.hasExplanation ?? false,
          hasComplexityCheck: loaded.hasComplexityCheck ?? false,
        })
        codeRef.current =
          loaded.response.kind === 'code' || loaded.response.kind === 'query'
            ? loaded.response.stub
            : ''
      })
      .catch((error: unknown) => {
        if (!cancelled) setLoadError(error instanceof Error ? error.message : String(error))
      })
    return () => {
      cancelled = true
      // Leaving an unsolved sitting records it as abandoned, then refresh history.
      void abandonActive().then(refreshHistory)
    }
  }, [selectedId, language, abandonActive, refreshHistory])

  // Ensure a sitting is open for the current exercise, starting one lazily on the
  // first Run so glancing at an exercise never creates an empty attempt.
  async function ensureAttempt(exerciseId: string): Promise<string> {
    if (attemptRef.current) return attemptRef.current.id
    const response = await apiFetch(`/api/attempts`, {
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
    const submission =
      exercise.response.kind === 'code' || exercise.response.kind === 'query'
        ? codeRef.current
        : exercise.response.kind === 'choice'
          ? (choice ?? '')
          : text
    setRun({ phase: 'running' })
    // A fresh run makes any previously revealed failing case stale; drop it and any
    // half-typed hypothesis so the reveal always reflects the current code. A previously
    // shown explanation is likewise stale until this run's verdict decides it.
    setFailingCase(null)
    setRevealPrompting(false)
    setExplanation(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/submissions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        // `language` (issue #26) is meaningful only to a code submission; the backend
        // simply ignores it for every other response kind.
        body: JSON.stringify({ submission, language }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const verdict = (await response.json()) as Verdict
      if (verdict.outcome === 'PASSED') {
        if (attemptRef.current) attemptRef.current.solved = true
        setSolved(true)
        setConsecutiveFailures(0)
        onSolved?.()
      } else {
        setConsecutiveFailures((n) => n + 1)
      }
      // A wrong answer discloses the explanation automatically (issue #51); a pass
      // withholds it, leaving it one keystroke away via the request button.
      if (verdict.explanation) setExplanation(verdict.explanation)
      setRun({ phase: 'done', verdict })
      refreshHistory()
    } catch (error: unknown) {
      setRun({ phase: 'error', message: error instanceof Error ? error.message : String(error) })
    }
  }

  // Take the next hint rung. Recorded on the attempt, never penalised (issue #16).
  async function handleTakeHint() {
    if (!exercise) return
    setHintBusy(true)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/hints`, {
        method: 'POST',
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const hint = (await response.json()) as HintResponse
      if (hint.name && hint.body) {
        setRevealedHints((taken) => [...taken, { name: hint.name!, body: hint.body! }])
      }
      refreshHistory()
    } catch {
      // Help is best-effort; a failure here should not disrupt solving.
    } finally {
      setHintBusy(false)
    }
  }

  // Reveal the failing case for the current code, recording the reveal and the
  // one-line hypothesis typed first. Both are recorded, never penalised (issue #16).
  async function handleReveal() {
    if (!exercise) return
    const submission =
      exercise.response.kind === 'code'
        ? codeRef.current
        : exercise.response.kind === 'choice'
          ? (choice ?? '')
          : text
    setRevealBusy(true)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/reveal`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ submission, hypothesis, language }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const revealed = (await response.json()) as RevealResponse
      setFailingCase(revealed.failingCase ?? { input: null, expected: null, note: 'no-case' })
      setRevealPrompting(false)
      refreshHistory()
    } catch {
      // Best-effort; leave the prompt open so the solver can retry.
    } finally {
      setRevealBusy(false)
    }
  }

  // Request the check's explanation when correct (issue #51). Recorded as its own
  // confidence signal, distinct from taking a hint, and never penalised.
  async function handleRequestExplanation() {
    if (!exercise) return
    setExplanationBusy(true)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/explanation`, {
        method: 'POST',
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const disclosed = (await response.json()) as ExplanationResponse
      if (disclosed.explanation) setExplanation(disclosed.explanation)
      refreshHistory()
    } catch {
      // Best-effort; a failure here should not disrupt the solved state.
    } finally {
      setExplanationBusy(false)
    }
  }

  // Reveal the reference solution on request (issue #82). Always available, always
  // recorded, never penalised - but a reveal before this attempt has passed marks it
  // solution-seen, so the panel presents that case distinctly from a free post-pass look.
  async function handleRevealSolution() {
    if (!exercise) return
    setSolutionBusy(true)
    setSolutionError(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/solution`, {
        method: 'POST',
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      const disclosed = (await response.json()) as SolutionResponse
      setSolution(disclosed.solution ?? null)
      setSolutionPrePass(disclosed.prePass)
      refreshHistory()
    } catch (error: unknown) {
      setSolutionError(error instanceof Error ? error.message : String(error))
    } finally {
      setSolutionBusy(false)
    }
  }

  // Submit the complexity self-report and, in the same response, reveal the authored
  // target and what measurement found (issue #17). The target is not sent until this
  // call returns - it was never in any earlier response this page holds - so stating
  // the claim first is a real ordering guarantee, not just a UI convention.
  async function handleClaimComplexity() {
    if (!exercise) return
    setComplexityBusy(true)
    setComplexityError(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/complexity`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ time: complexityTimeClaim, space: complexitySpaceClaim }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      setComplexityResult((await response.json()) as ComplexityResponse)
      refreshHistory()
    } catch (error: unknown) {
      setComplexityError(error instanceof Error ? error.message : String(error))
    } finally {
      setComplexityBusy(false)
    }
  }

  // Ask a model for an independent reading of a solved attempt's complexity (issue
  // #83) - one short call, on request, always after the claim above so the model's
  // own reading can be compared against it. Advisory only: the result is never
  // stored anywhere, and this action only ever renders when the claim response says
  // modelOpinionAvailable, so pressing it never reaches a server with no key.
  async function handleRequestModelOpinion() {
    if (!exercise) return
    setModelOpinionBusy(true)
    setModelOpinionError(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/complexity/model-opinion`, {
        method: 'POST',
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      setModelOpinionResult((await response.json()) as ModelOpinionResponse)
    } catch (error: unknown) {
      setModelOpinionError(error instanceof Error ? error.message : String(error))
    } finally {
      setModelOpinionBusy(false)
    }
  }

  // Commit the produced explanation and reveal the model answer (issue #41). Nothing is
  // machine-graded: the text is frozen server-side before the answer comes back, so a later
  // self-rating cannot be a copy of what was peeked at.
  async function handleRevealModelAnswer() {
    if (!exercise) return
    setSelfCheckBusy(true)
    setSelfCheckError(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/self-check/reveal`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ produced: text }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      setReveal((await response.json()) as SelfCheckReveal)
    } catch (error: unknown) {
      setSelfCheckError(error instanceof Error ? error.message : String(error))
    } finally {
      setSelfCheckBusy(false)
    }
  }

  // Record the learner's self-rating against the committed submission and end the sitting
  // EXPLAINED (issue #41). It is a generation signal only - it never touches any score.
  async function handleSelfRate(rating: SelfRating) {
    if (!exercise || !reveal) return
    setSelfCheckBusy(true)
    setSelfCheckError(null)
    try {
      const attemptId = await ensureAttempt(exercise.id)
      const response = await apiFetch(`/api/attempts/${attemptId}/self-check/rating`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ submission: reveal.submissionId, rating }),
      })
      if (!response.ok) throw new Error(await errorMessage(response))
      // The sitting is now terminal; mark it so switch-away never tries to abandon it, and
      // let the session know an optional-main item was completed.
      if (attemptRef.current) attemptRef.current.solved = true
      setSelfRating(rating)
      onSolved?.()
      refreshHistory()
    } catch (error: unknown) {
      setSelfCheckError(error instanceof Error ? error.message : String(error))
    } finally {
      setSelfCheckBusy(false)
    }
  }

  async function handleGiveUp() {
    await abandonActive()
    setSolved(false)
    setRun({ phase: 'idle' })
    setRevealedHints([])
    setFailingCase(null)
    setRevealPrompting(false)
    setHypothesis('')
    setConsecutiveFailures(0)
    setExplanation(null)
    setComplexityResult(null)
    setComplexityError(null)
    setSolution(null)
    setSolutionPrePass(false)
    setSolutionError(null)
    setModelOpinionResult(null)
    setModelOpinionError(null)
    refreshHistory()
  }

  // The family filter group is built from whatever family tags the loaded catalog
  // actually carries - no fixed enum baked into the client, so an untagged content set
  // simply shows no family group at all rather than a row of always-inactive chips.
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

  const onToggleFilter = useCallback((groupKey: string, value: string) => {
    const setter = groupKey === 'family' ? setFamilyFilter : setDifficultyFilter
    setter((prev) => {
      const next = new Set(prev)
      if (next.has(value)) next.delete(value)
      else next.add(value)
      return next
    })
  }, [])

  // Real per-exercise completion from this user's own attempt history - never a
  // fabricated per-item signal (issue #7). Practice already loads this for the
  // history table below, so the tree's completion bars are free.
  const solvedIds = useMemo(
    () => new Set(history.filter((a) => a.outcome === 'SOLVED').map((a) => a.exerciseId)),
    [history],
  )

  if (catalogError) {
    return (
      <>
        <h1>{APP_NAME}</h1>
        <p className="status down">Could not load exercises: {catalogError}</p>
      </>
    )
  }

  if (!catalog) {
    return (
      <>
        <h1>{APP_NAME}</h1>
        <p className="status loading">Loading exercises...</p>
      </>
    )
  }

  return (
    <>
      <div className="browsehead">
        <div>
          <h1>Practice</h1>
          <p>
            {dayComplete ? 'Your day is already complete - ' : ''}Take on a main exercise, then
            keep going as long as you like. There is no cap here.
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
          // The grid above can run to hundreds of cards (issue #90 kept Practice's
          // existing unfiltered catalog scope - only the navigation changed), so
          // without this a selection could land far below the fold, behind the very
          // grid the solver just picked from. The old flat `<select>` never had this
          // problem: it was only ever a few lines tall.
          exerciseSectionRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
        }}
        findLabel="Find a problem"
        findPlaceholder="two sum, koko, window…"
        emptyMessage="No exercises available."
        sectionLabel="Practice"
        itemNoun="problem"
        solvedIds={solvedIds}
      />

      <div ref={exerciseSectionRef}>
      {loadError && <p className="status down">Could not load the exercise: {loadError}</p>}
      {!exercise && !loadError && <p className="status loading">Loading exercise...</p>}

      {exercise && (
        <div className="exgrid">
        <div className="card exl">
          <header>
            <h1>{exercise.title}</h1>
            {exercise.response.kind === 'code' ? (
              <LanguagePicker
                language={language}
                languages={languages}
                disabled={run.phase === 'running'}
                onChange={setLanguage}
              />
            ) : (
              <span className="language-tag">
                {exercise.response.kind === 'query' ? exercise.response.language : exercise.domain}
              </span>
            )}
          </header>
          <p className="statement">{exercise.statement}</p>

          {exercise.response.kind === 'selfCheck' ? (
            <SelfCheckView
              text={text}
              reveal={reveal}
              rating={selfRating}
              busy={selfCheckBusy}
              error={selfCheckError}
              onType={setText}
              onReveal={handleRevealModelAnswer}
              onRate={handleSelfRate}
            />
          ) : (
            <>
              {exercise.response.kind === 'code' || exercise.response.kind === 'query' ? (
                <div className="editor">
                  <Editor
                    // Keyed on language too (issue #26): switching languages regenerates
                    // the stub, and Monaco only applies defaultValue/defaultLanguage on
                    // mount, so the editor must remount to actually show the new one.
                    key={`${exercise.id}:${exercise.response.language}`}
                    height="360px"
                    theme={prefersDark ? 'vs-dark' : 'light'}
                    defaultLanguage={exercise.response.language}
                    defaultValue={exercise.response.stub}
                    onChange={(value) => {
                      codeRef.current = value ?? ''
                    }}
                    options={{ minimap: { enabled: false }, fontSize: 14 }}
                  />
                </div>
              ) : exercise.response.kind === 'freeText' ? (
                <div className="freetext">
                  <label htmlFor="free-answer">Type your answer</label>
                  <input
                    id="free-answer"
                    type="text"
                    className="hypothesis"
                    value={text}
                    disabled={solved}
                    onChange={(event) => setText(event.target.value)}
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
                    (exercise.response.kind === 'choice' && choice === null) ||
                    (exercise.response.kind === 'freeText' && text.trim() === '')
                  }
                >
                  {run.phase === 'running'
                    ? 'Checking...'
                    : exercise.response.kind === 'code' || exercise.response.kind === 'query'
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

              <VerdictView
                run={run}
                scored={exercise.response.kind !== 'code' && exercise.response.kind !== 'query'}
                unit={exercise.response.kind === 'query' ? 'rows' : 'tests'}
              />

              {!solved && (
                <HintLadder
                  rungNames={exercise.hints}
                  revealed={revealedHints}
                  busy={hintBusy}
                  stuck={
                    consecutiveFailures >= STUCK_NUDGE_AFTER_FAILURES &&
                    revealedHints.length < exercise.hints.length
                  }
                  onTakeHint={handleTakeHint}
                />
              )}

              {exercise.response.kind === 'code' &&
                !solved &&
                run.phase === 'done' &&
                run.verdict.outcome === 'FAILED' && (
                  <RevealPanel
                    prompting={revealPrompting}
                    busy={revealBusy}
                    hypothesis={hypothesis}
                    failingCase={failingCase}
                    onHypothesisChange={setHypothesis}
                    onStart={() => setRevealPrompting(true)}
                    onConfirm={handleReveal}
                    onCancel={() => setRevealPrompting(false)}
                  />
                )}

              {exercise.response.kind === 'code' && (
                <SolutionPanel
                  solution={solution}
                  prePass={solutionPrePass}
                  busy={solutionBusy}
                  error={solutionError}
                  onReveal={handleRevealSolution}
                />
              )}

              {exercise.response.kind === 'code' && exercise.hasComplexityCheck && solved && (
                <ComplexityPanel
                  timeClaim={complexityTimeClaim}
                  spaceClaim={complexitySpaceClaim}
                  result={complexityResult}
                  busy={complexityBusy}
                  error={complexityError}
                  onTimeClaimChange={setComplexityTimeClaim}
                  onSpaceClaimChange={setComplexitySpaceClaim}
                  onSubmit={handleClaimComplexity}
                  modelOpinion={modelOpinionResult}
                  modelOpinionBusy={modelOpinionBusy}
                  modelOpinionError={modelOpinionError}
                  onRequestModelOpinion={handleRequestModelOpinion}
                />
              )}

              <ExplanationPanel
                hasExplanation={exercise.hasExplanation}
                explanation={explanation}
                solved={solved}
                busy={explanationBusy}
                onRequest={handleRequestExplanation}
              />
            </>
          )}
        </div>
        </div>
      )}
      </div>

      <History attempts={history} />
    </>
  )
}

// The self-graded "explain in your own words" produce-then-reveal flow (issue #41). You
// write your explanation, commit it to reveal the model answer, then rate yourself against
// it. Nothing here is machine-graded and no rating touches any score - it is production
// practice the recognition reps cannot give, kept structurally out of the competence signal.
function SelfCheckView({
  text,
  reveal,
  rating,
  busy,
  error,
  onType,
  onReveal,
  onRate,
}: {
  text: string
  reveal: SelfCheckReveal | null
  rating: SelfRating | null
  busy: boolean
  error: string | null
  onType: (value: string) => void
  onReveal: () => void
  onRate: (rating: SelfRating) => void
}) {
  const ratingLabels: Record<SelfRating, string> = {
    NAILED_IT: 'Nailed it',
    PARTIAL: 'Partial',
    MISSED: 'Missed it',
  }
  return (
    <section className="self-check">
      <div className="freetext">
        <label htmlFor="explain-answer">Explain it in your own words</label>
        <textarea
          id="explain-answer"
          className="explain-box"
          rows={6}
          value={text}
          disabled={reveal !== null}
          placeholder="Write your explanation before revealing the model answer."
          onChange={(event) => onType(event.target.value)}
        />
      </div>

      {error && <p className="status down">Could not save: {error}</p>}

      {reveal === null ? (
        <div className="actions">
          <button type="button" onClick={onReveal} disabled={busy || text.trim() === ''}>
            {busy ? 'Revealing...' : 'Reveal the model answer'}
          </button>
          <p className="hints-note">
            Producing it cold first is the point - the interview asks you to explain, not to
            recognise. Nothing here is graded.
          </p>
        </div>
      ) : (
        <>
          <section className="explanation">
            <h2>Model answer</h2>
            <p className="explanation-body">{reveal.modelAnswer}</p>
          </section>

          {rating === null ? (
            <section className="self-rating">
              <h2>How did yours compare?</h2>
              <p className="hints-note">
                This is for you - it is recorded as a generation signal, never a score, and it
                can never move your objective progress.
              </p>
              <div className="actions">
                {(Object.keys(ratingLabels) as SelfRating[]).map((value) => (
                  <button
                    key={value}
                    type="button"
                    className="secondary"
                    disabled={busy}
                    onClick={() => onRate(value)}
                  >
                    {ratingLabels[value]}
                  </button>
                ))}
              </div>
            </section>
          ) : (
            <p className="status up">
              Explanation recorded ({ratingLabels[rating]}). That is production practice done -
              no score changed.
            </p>
          )}
        </>
      )}
    </section>
  )
}

function VerdictView({
  run,
  scored,
  unit = 'tests',
}: {
  run: RunState
  scored: boolean
  // Which noun the unscored count is reported in - 'tests' for a code exercise,
  // 'rows' for a SQL query (issue #25), where the disclosed signal is literally the
  // actual row count against the expected one, not "rows matched" (withhold-by-default
  // judging never implies partial credit by content, issues #16/#5).
  unit?: 'tests' | 'rows'
}) {
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
        : unit === 'rows'
          ? `Returned ${verdict.passed} row${verdict.passed === 1 ? '' : 's'}, expected ${verdict.total}`
          : `${verdict.passed} of ${verdict.total} tests passed`
      return (
        <p className={`status ${verdict.outcome === 'PASSED' ? 'up' : 'down'}`}>
          {label}
          <Runtime millis={verdict.runtimeMillis} />
          {verdict.solutionCommitted && (
            <span className="solution-committed"> · committed to your solutions repo</span>
          )}
        </p>
      )
    }
    case 'COMPILE_ERROR':
      return (
        <div className="verdict-block down">
          <p className="status down">{unit === 'rows' ? 'Query error' : 'Compile error'}</p>
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

// Runtime shown next to the verdict, for interest only - it is never part of the
// verdict (issue #16/#5). Omitted when there is nothing to show (an answer-key run
// reports 0) so it never reads as a graded quantity.
function Runtime({ millis }: { millis?: number }) {
  if (millis === undefined || millis <= 0) return null
  return <span className="runtime"> · {millis} ms</span>
}

// The language picker (issue #26): every code exercise can be solved in any language
// the backend reports as available, Java selected by default. Changing it regenerates
// the stub and harness from the exercise's own language-neutral signature - nothing
// about the stored problem or its test cases changes, only which adapter renders it.
function LanguagePicker({
  language,
  languages,
  disabled,
  onChange,
}: {
  language: string
  languages: string[]
  disabled: boolean
  onChange: (language: string) => void
}) {
  return (
    <label className="language-picker">
      <span className="visually-hidden">Language</span>
      <select
        aria-label="Language"
        value={language}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      >
        {languages.map((id) => (
          <option key={id} value={id}>
            {id}
          </option>
        ))}
      </select>
    </label>
  )
}

// The hint ladder (issue #16): help is always available, always chosen, always
// recorded, and never penalised. Each rung is taken explicitly and its body arrives
// only then. When repeated runs fail, an unobtrusive nudge offers the next rung - it
// reads as available help, never a reprimand, and taking it costs nothing.
function HintLadder({
  rungNames,
  revealed,
  busy,
  stuck,
  onTakeHint,
}: {
  rungNames: string[]
  revealed: RevealedHint[]
  busy: boolean
  stuck: boolean
  onTakeHint: () => void
}) {
  if (rungNames.length === 0) return null
  const nextRung = rungNames[revealed.length]
  const allTaken = revealed.length >= rungNames.length
  return (
    <section className="hints">
      <h2>Hints</h2>
      <p className="hints-note">
        Hints are here whenever you want them. Taking one is recorded, but it never
        affects your score.
      </p>
      <ol className="hint-list">
        {revealed.map((hint) => (
          <li key={hint.name}>
            <span className="hint-name">{hint.name}</span>
            <span className="hint-body">{hint.body}</span>
          </li>
        ))}
      </ol>
      {!allTaken && (
        <div className={`hint-offer${stuck ? ' nudge' : ''}`}>
          {stuck && (
            <p className="nudge-line">
              Stuck? There is no penalty for a hint - the next one just names {nextRung}.
            </p>
          )}
          <button type="button" className="secondary" onClick={onTakeHint} disabled={busy}>
            {busy
              ? 'Revealing...'
              : revealed.length === 0
                ? `Reveal a hint (${nextRung})`
                : `Reveal the next hint (${nextRung})`}
          </button>
        </div>
      )}
      {allTaken && <p className="hints-note">You have taken every hint for this exercise.</p>}
    </section>
  )
}

// The failing-case reveal (issues #16/#5). By default a failing run says only how
// many cases failed. Choosing to reveal first asks for a one-line hypothesis - an act
// of generation, ungraded and skippable - then discloses the case's input, expected
// and actual. The reveal is recorded, never penalised.
function RevealPanel({
  prompting,
  busy,
  hypothesis,
  failingCase,
  onHypothesisChange,
  onStart,
  onConfirm,
  onCancel,
}: {
  prompting: boolean
  busy: boolean
  hypothesis: string
  failingCase: FailingCase | null
  onHypothesisChange: (value: string) => void
  onStart: () => void
  onConfirm: () => void
  onCancel: () => void
}) {
  if (failingCase) {
    if (failingCase.note === 'no-case') {
      return (
        <section className="reveal">
          <p className="hints-note">
            There was no single failing case to show - the current code did not run to a
            comparable result. The reveal was still recorded.
          </p>
        </section>
      )
    }
    return (
      <section className="reveal">
        <h2>Failing case</h2>
        <dl className="failing-case">
          <dt>Input</dt>
          <dd>{JSON.stringify(failingCase.input)}</dd>
          <dt>Expected</dt>
          <dd>{JSON.stringify(failingCase.expected)}</dd>
          <dt>Actual</dt>
          <dd>
            {failingCase.note ? failingCase.note : JSON.stringify(failingCase.actual)}
          </dd>
        </dl>
      </section>
    )
  }

  if (prompting) {
    return (
      <section className="reveal">
        <label className="reveal-prompt" htmlFor="hypothesis">
          Before you look: in one line, what do you think is wrong? (optional, ungraded)
        </label>
        <input
          id="hypothesis"
          type="text"
          className="hypothesis"
          value={hypothesis}
          placeholder="e.g. it fails on an empty input"
          onChange={(event) => onHypothesisChange(event.target.value)}
        />
        <div className="actions">
          <button type="button" onClick={onConfirm} disabled={busy}>
            {busy ? 'Revealing...' : 'Show the failing case'}
          </button>
          <button type="button" className="secondary" onClick={onCancel} disabled={busy}>
            Not yet
          </button>
        </div>
      </section>
    )
  }

  return (
    <section className="reveal">
      <button type="button" className="secondary" onClick={onStart}>
        Reveal the failing case
      </button>
      <p className="hints-note">
        Revealing is recorded but never penalised. Reasoning it out first is the skill an
        interview tests.
      </p>
    </section>
  )
}

// The complexity self-report flow (issue #17): state a time and space complexity
// claim, then - and only then - the authored target and the empirical measurement
// result are revealed. Articulating complexity before seeing the answer is the
// interview skill this trains, so the claim is a real gate, not a formality: the
// target simply is not in this page's hands until the claim is submitted.
// The Direction A graft (issue #90): "the app measures scaling empirically and
// currently reports it as the word CONSISTENT. Drawing the curve is the most
// interesting screen in the product and it is free: ScalingMeasurer already has the
// per-size medians." Renders straight from the measurement data the backend now
// returns - no re-measuring, no re-deriving anything client-side. Log-log axes, so a
// true power-law relationship (time ~ size^p) plots as a straight line of slope p.
function ComplexityPlot({
  points,
  exponent,
  confidenceHalfWidth,
}: {
  points: MeasurementPoint[]
  exponent?: number
  confidenceHalfWidth?: number
}) {
  const sizes = points.map((p) => p.size)
  const millis = points.map((p) => Math.max(p.millis, 0.001)) // guard log(0)
  const minSize = Math.min(...sizes)
  const maxSize = Math.max(...sizes)
  const minMs = Math.min(...millis)
  const maxMs = Math.max(...millis)
  // Pad the axes a little past the data so the outermost points are never drawn on
  // the plot's own border.
  const logMinX = Math.log(minSize) - 0.2
  const logMaxX = Math.log(maxSize) + 0.2
  const logMinY = Math.log(minMs) - 0.3
  const logMaxY = Math.log(maxMs) + 0.3
  const width = 524
  const height = 216
  const left = 52
  const right = 504
  const top = 20
  const bottom = 186
  const x = (size: number) => left + ((Math.log(size) - logMinX) / (logMaxX - logMinX)) * (right - left)
  const y = (ms: number) => bottom - ((Math.log(ms) - logMinY) / (logMaxY - logMinY)) * (bottom - top)

  const sorted = [...points].sort((a, b) => a.size - b.size)
  const first = sorted[0]
  const last = sorted[sorted.length - 1]

  return (
    <div className="plot">
      <svg
        viewBox={`0 0 ${width} ${height}`}
        role="img"
        aria-label={`Log-log plot of runtime against input size${
          exponent !== undefined ? `, slope ${exponent.toFixed(2)}` : ''
        }`}
      >
        {[0, 1, 2, 3, 4].map((i) => (
          <line
            key={i}
            x1={left}
            y1={top + i * ((bottom - top) / 4)}
            x2={right}
            y2={top + i * ((bottom - top) / 4)}
            stroke="var(--line2)"
            strokeWidth="1"
            opacity="0.55"
          />
        ))}
        <line x1={left} y1={bottom} x2={right} y2={bottom} stroke="var(--line2)" strokeWidth="1.4" />
        <line x1={left} y1={top} x2={left} y2={bottom} stroke="var(--line2)" strokeWidth="1.4" />
        {exponent !== undefined && (
          <line
            x1={x(first.size)}
            y1={y(Math.max(first.millis, 0.001))}
            x2={x(last.size)}
            y2={y(
              Math.max(
                Math.exp(Math.log(Math.max(first.millis, 0.001)) + exponent * (Math.log(last.size) - Math.log(first.size))),
                0.001,
              ),
            )}
            stroke="var(--emerald)"
            strokeWidth="2"
          />
        )}
        {points.map((p, i) => (
          <circle key={i} cx={x(p.size)} cy={y(Math.max(p.millis, 0.001))} r="4" fill="var(--primary)" />
        ))}
        {sorted.map((p) => (
          <text
            key={p.size}
            x={x(p.size)}
            y={bottom + 17}
            textAnchor="middle"
            fontSize="10"
            fill="var(--faint)"
            fontFamily="var(--mono)"
          >
            {p.size >= 1000 ? `${p.size / 1000}k` : p.size}
          </text>
        ))}
        <text x={left} y={13} fontSize="10" fill="var(--faint)" fontFamily="var(--mono)">
          runtime, ms (log scale) vs input size (log scale)
        </text>
        {exponent !== undefined && (
          <text x={left + 66} y={y(maxMs) + 22} fontSize="11.5" fill="var(--emerald)" fontFamily="var(--mono)" fontWeight="700">
            slope {exponent.toFixed(2)}
            {confidenceHalfWidth !== undefined ? ` ± ${confidenceHalfWidth.toFixed(2)}` : ''}
          </text>
        )}
      </svg>
      <p className="note">
        {points.length} input sizes measured, fitted on log-log axes.
        {exponent !== undefined && confidenceHalfWidth !== undefined
          ? ` A slope of ${exponent.toFixed(2)} with a confidence interval of ±${confidenceHalfWidth.toFixed(2)} sits inside one growth-rate band, so the measurement is consistent with the reported bucket. It could never separate O(n) from O(n log n), and it does not pretend to.`
          : ''}
      </p>
    </div>
  )
}

function ComplexityPanel({
  timeClaim,
  spaceClaim,
  result,
  busy,
  error,
  onTimeClaimChange,
  onSpaceClaimChange,
  onSubmit,
  modelOpinion,
  modelOpinionBusy,
  modelOpinionError,
  onRequestModelOpinion,
}: {
  timeClaim: Complexity
  spaceClaim: Complexity
  result: ComplexityResponse | null
  busy: boolean
  error: string | null
  onTimeClaimChange: (value: Complexity) => void
  onSpaceClaimChange: (value: Complexity) => void
  onSubmit: () => void
  modelOpinion: ModelOpinionResponse | null
  modelOpinionBusy: boolean
  modelOpinionError: string | null
  onRequestModelOpinion: () => void
}) {
  if (result) {
    return (
      <section className="complexity">
        <h2>Complexity</h2>
        <dl className="complexity-target">
          <dt>Authored target</dt>
          <dd>
            {COMPLEXITY_LABELS[result.targetTime]} time, {COMPLEXITY_LABELS[result.targetSpace]} space
          </dd>
        </dl>
        {result.status === 'CONSISTENT' && (
          <p className="status up">Measured scaling is consistent with your claim.</p>
        )}
        {result.status === 'CONTRADICTED' && (
          <p className="status down">
            Measured scaling does not match your claim - worth a second look at how your
            solution scales.
          </p>
        )}
        {result.status === 'INCONCLUSIVE' && (
          <p className="status loading">
            Measurement was inconclusive{result.detail ? `: ${result.detail}` : '.'} That is not
            a verdict either way - some growth rates (like O(n) vs O(n log n)) genuinely cannot
            be told apart by timing alone.
          </p>
        )}
        {result.status === 'SKIPPED' && (
          <p className="hints-note">This exercise has no automated scaling check for your claim.</p>
        )}
        {result.points && result.points.length >= 2 && (
          <ComplexityPlot
            points={result.points}
            exponent={result.exponent}
            confidenceHalfWidth={result.confidenceHalfWidth}
          />
        )}
        {result.modelOpinionAvailable && (
          <ModelOpinionSection
            result={modelOpinion}
            busy={modelOpinionBusy}
            error={modelOpinionError}
            onRequest={onRequestModelOpinion}
          />
        )}
      </section>
    )
  }

  return (
    <section className="complexity">
      <h2>What is your solution's complexity?</h2>
      <p className="hints-note">
        State it before the answer is revealed - articulating complexity is itself an
        interview skill. Your claim is checked by measuring how your solution actually
        scales.
      </p>
      <div className="complexity-claim">
        <label htmlFor="complexity-time">Time</label>
        <select
          id="complexity-time"
          value={timeClaim}
          disabled={busy}
          onChange={(event) => onTimeClaimChange(event.target.value as Complexity)}
        >
          {COMPLEXITY_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {COMPLEXITY_LABELS[option]}
            </option>
          ))}
        </select>
        <label htmlFor="complexity-space">Space</label>
        <select
          id="complexity-space"
          value={spaceClaim}
          disabled={busy}
          onChange={(event) => onSpaceClaimChange(event.target.value as Complexity)}
        >
          {COMPLEXITY_OPTIONS.map((option) => (
            <option key={option} value={option}>
              {COMPLEXITY_LABELS[option]}
            </option>
          ))}
        </select>
      </div>
      {error && <p className="status down">Could not record your claim: {error}</p>}
      <div className="actions">
        <button type="button" className="secondary" onClick={onSubmit} disabled={busy}>
          {busy ? 'Checking...' : 'Submit complexity claim'}
        </button>
      </div>
    </section>
  )
}

// The reference-solution reveal (issue #82): available on request at any time, recorded,
// never penalised. Pre-pass and post-pass are deliberately distinct presentations - seeing
// the solution before this attempt has ever passed marks it solution-seen (a plain,
// honest note, not a warning), while a post-pass look is framed as the comparison it is
// meant to encourage.
function SolutionPanel({
  solution,
  prePass,
  busy,
  error,
  onReveal,
}: {
  solution: string | null
  prePass: boolean
  busy: boolean
  error: string | null
  onReveal: () => void
}) {
  if (solution) {
    return (
      <section className="solution">
        <h2>Reference solution</h2>
        {prePass ? (
          <p className="hints-note">
            Seeing this is recorded - it never counts against you, but this attempt will not
            count as solved cold until you pass a fresh one cleanly, and it comes back around
            sooner.
          </p>
        ) : (
          <p className="hints-note">
            Reading a working solution against your own passing one is where style and idiom
            learning happens.
          </p>
        )}
        <pre className="solution-body">{solution}</pre>
      </section>
    )
  }
  return (
    <section className="solution">
      <button type="button" className="secondary" onClick={onReveal} disabled={busy}>
        {busy ? 'Revealing...' : 'Show the reference solution'}
      </button>
      <p className="hints-note">
        Available whenever you want it. Revealing before you pass marks this attempt
        solution-seen and brings it back around sooner - never a penalty, just an honest note.
      </p>
      {error && <p className="status down">Could not reveal the solution: {error}</p>}
    </section>
  )
}

// The LLM complexity second opinion (issue #83): a third, advisory voice beside the
// claim and the measurement above, on request only. Disagreement is the product - if
// the model, the claim and the measurement all agree there is nothing to show beyond
// quiet confirmation; any disagreement renders as a neutral question for the learner
// to resolve in their own words, never as a statement of which voice is right. Only
// ever rendered by the parent when the claim response says modelOpinionAvailable, so
// this action never reaches a server with no key configured.
function ModelOpinionSection({
  result,
  busy,
  error,
  onRequest,
}: {
  result: ModelOpinionResponse | null
  busy: boolean
  error: string | null
  onRequest: () => void
}) {
  if (!result) {
    return (
      <div className="model-opinion">
        <button type="button" className="secondary" onClick={onRequest} disabled={busy}>
          {busy ? 'Asking...' : 'Get a second opinion'}
        </button>
        <p className="hints-note">
          Asks a model for its own read of your solution's complexity - advisory only, and
          never part of your score. Models can misjudge amortised analysis, memoised
          recursion, and hidden costs like string concatenation or {'List.contains'} in a
          loop, so treat disagreement as something to work out, not as a tiebreaker.
        </p>
        {error && <p className="status down">Could not get a second opinion: {error}</p>}
      </div>
    )
  }

  if (result.agreement) {
    return (
      <p className="status up">
        The model agrees: {COMPLEXITY_LABELS[result.modelTime]} time.
      </p>
    )
  }

  return (
    <div className="hint-offer nudge model-opinion-disagreement">
      <p className="nudge-line">{result.disagreementPrompt}</p>
      <p className="explanation-body">{result.modelReasoning}</p>
    </div>
  )
}

// The check's explanation (issue #51): why the correct answer is correct, kept separate
// from the hint ladder. It is shown automatically on a wrong answer (the parent hands
// the disclosed text down); when the answer is correct it is one keystroke away, and
// asking is recorded as its own confidence signal - never a penalty, and never a hint.
function ExplanationPanel({
  hasExplanation,
  explanation,
  solved,
  busy,
  onRequest,
}: {
  hasExplanation: boolean
  explanation: string | null
  solved: boolean
  busy: boolean
  onRequest: () => void
}) {
  if (explanation) {
    return (
      <section className="explanation">
        <h2>Why this is the answer</h2>
        <p className="explanation-body">{explanation}</p>
      </section>
    )
  }
  // Offer it only once correct - a wrong answer would have shown it automatically.
  if (hasExplanation && solved) {
    return (
      <section className="explanation">
        <button type="button" className="secondary" onClick={onRequest} disabled={busy}>
          {busy ? 'Revealing...' : 'Why is this the answer?'}
        </button>
        <p className="hints-note">
          Asking is recorded, but it never affects your score - it is not a hint.
        </p>
      </section>
    )
  }
  return null
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
            <th>Hints</th>
            <th>Revealed</th>
            <th>Solution seen</th>
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
              <td>{attempt.hintsTaken > 0 ? attempt.hintsTaken : '-'}</td>
              <td>{attempt.failingCaseRevealed ? 'yes' : '-'}</td>
              <td>{attempt.solutionSeen ? 'yes' : '-'}</td>
              <td>{new Date(attempt.startedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}

export default Practice
