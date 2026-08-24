import { useCallback, useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'
import { familyLabel } from './familyLabels'
import { fetchSessionHistory, type DayHistory } from './sessionHistory'

// The honest readiness picture (issue #45, design revision t3 section 4.4) - the primary
// progress surface the map's "no invented currency" ruling (issue #7) replaces points,
// badges and leagues with. Every number here is either a plain "X of Y" against a real
// catalog total, or a bare count; nothing is a score.
//
// The three-way separation the ticket requires is rendered structurally, not just labeled:
// the two objective competence axes and the per-family breakdown come only from machine
// verdicts (checksToCriterion, solvedCold), concepts-covered is its own axis over Lessons
// read, and the self-check "explained" count sits in its own section with its own note
// that it is never added into the axes above it.
//
// Issue #22 adds two more plain lists, not scores: shakyTopics (attempted patterns not
// yet reliable) and staleTopics (attempted patterns not touched in a while). Each renders
// only when non-empty - a clean readiness picture shows neither section at all.

type Progress = { achieved: number; total: number }
type FamilyReadiness = { family: string; checksToCriterion: Progress; solvedCold: Progress }
type StaleTopic = { topic: string; daysSinceTouched: number }
type ReadinessSummary = {
  checksToCriterion: Progress
  solvedCold: Progress
  conceptsCovered: Progress
  selfCheckExplainedCount: number
  families: FamilyReadiness[]
  // Issue #22: attempted-but-unreliable and attempted-but-stale topics. Both are absent
  // from older cached responses, so they are read defensively as empty rather than assumed.
  shakyTopics?: string[]
  staleTopics?: StaleTopic[]
}

const ALWAYS_ACTIVE = new Set(['CORE', 'PROFESSIONAL'])

function Readiness({ streak }: { streak?: number }) {
  const [summary, setSummary] = useState<ReadinessSummary | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => {
    apiFetch('/api/readiness')
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as ReadinessSummary
      })
      .then(setSummary)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  if (error) {
    return (
      <section className="readiness">
        <h1>Readiness</h1>
        <p className="status down">{error}</p>
      </section>
    )
  }

  if (!summary) {
    return (
      <section className="readiness">
        <h1>Readiness</h1>
        <p>Loading your readiness picture...</p>
      </section>
    )
  }

  // Only families with content in the local clone, plus the always-active substrate, so a
  // family with nothing loaded (issue #4/#14, content-not-cloned) is not shown as a row of
  // zeroes indistinguishable from "you have not touched this yet".
  const familyRows = summary.families.filter(
    (f) =>
      f.checksToCriterion.total > 0 || f.solvedCold.total > 0 || ALWAYS_ACTIVE.has(f.family),
  )

  return (
    <section className="readiness">
      <h1>Readiness</h1>
      <p className="readiness-intro">
        The honest picture of what you actually know, not a score. Every number below comes
        from real practice - no points, no badges, no levels.
      </p>

      <div className="readiness-axes">
        <ReadinessCard
          label="Checks to criterion"
          progress={summary.checksToCriterion}
          note="Recognition checks retrieved correctly across spaced sessions (issue #38)."
        />
        <ReadinessCard
          label="Solved cold"
          progress={summary.solvedCold}
          note="Challenges solved with no hint taken, no failing case revealed, and no reference solution seen."
        />
        <ReadinessCard
          label="Concepts covered"
          progress={summary.conceptsCovered}
          note="Lessons read - so reading counts as earned progress too."
        />
      </div>

      <YearGrid />

      <section className="self-check-count">
        <h2>
          Explained {summary.selfCheckExplainedCount}{' '}
          {summary.selfCheckExplainedCount === 1 ? 'concept' : 'concepts'}
        </h2>
        <p>
          Self-rated "explain in your own words" items. A separate, self-reported count - it
          is never added into the checks or challenges above, since only a machine verdict
          counts there.
        </p>
      </section>

      {((summary.shakyTopics?.length ?? 0) > 0 || (summary.staleTopics?.length ?? 0) > 0) && (
        <section className="topic-flags">
          {(summary.shakyTopics?.length ?? 0) > 0 && (
            <p className="shaky-topics">
              <strong>Shaky:</strong> {summary.shakyTopics!.join(', ')}
            </p>
          )}
          {(summary.staleTopics?.length ?? 0) > 0 && (
            <p className="stale-topics">
              <strong>Not touched in a while:</strong>{' '}
              {summary.staleTopics!.map((t) => `${t.topic} (${t.daysSinceTouched}d)`).join(', ')}
            </p>
          )}
        </section>
      )}

      {typeof streak === 'number' && (
        <p className="readiness-streak">
          {streak > 0
            ? `${streak}-day streak - a record of days you showed up, not a score.`
            : 'No streak yet - it starts building the next time you show up.'}
        </p>
      )}

      <section className="family-breakdown">
        <h2>By family</h2>
        <div className="family-table-wrap">
          <table className="family-table">
            <thead>
              <tr>
                <th>Family</th>
                <th>Checks to criterion</th>
                <th>Solved cold</th>
              </tr>
            </thead>
            <tbody>
              {familyRows.map((f) => (
                <tr key={f.family}>
                  <td>
                    {familyLabel(f.family)}
                    {ALWAYS_ACTIVE.has(f.family) && (
                      <span className="family-always"> (always)</span>
                    )}
                  </td>
                  <td>
                    {f.checksToCriterion.achieved}/{f.checksToCriterion.total}
                  </td>
                  <td>
                    {f.solvedCold.achieved}/{f.solvedCold.total}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </section>
  )
}

function ReadinessCard({
  label,
  progress,
  note,
}: {
  label: string
  progress: Progress
  note: string
}) {
  const pct = progress.total > 0 ? (progress.achieved / progress.total) * 100 : 0
  return (
    <div className="readiness-card axis">
      <h2>{label}</h2>
      <p className="readiness-count">
        {progress.achieved}/{progress.total}
      </p>
      <div className="tr" aria-hidden="true">
        <i style={{ width: `${pct}%` }} />
      </div>
      <p className="readiness-note">{note}</p>
    </div>
  )
}

// The Direction A graft: "the signature element is the year record - 182 day-cells...
// filled from real day_completion history. It is the honest streak made into a
// picture instead of a sentence." Reads the same GET /api/session/history the Today
// ribbon reads (issue #90) - one endpoint, two windows over one honest record.
function YearGrid() {
  const [days, setDays] = useState<DayHistory[] | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchSessionHistory()
      .then((history) => {
        if (!cancelled) setDays(history)
      })
      .catch(() => {
        // A secondary flourish; a failure here must never blank the readiness picture.
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (!days || days.length === 0) return null

  return (
    <section className="card yeargrid-section">
      <h2>The record</h2>
      <div className="yeargrid">
        {days.map((day, i) => (
          <i key={day.date} className={yearCellClass(day, i === days.length - 1)} title={day.date} />
        ))}
      </div>
      <p className="readiness-note">
        Every day you finished the warm-up, since the record began - a picture of what
        happened, not a score.
      </p>
    </section>
  )
}

function yearCellClass(day: DayHistory, isToday: boolean): string {
  const cls = [] as string[]
  if (isToday) cls.push('today')
  if (day.doubleSession) cls.push('dbl')
  else if (day.completed) cls.push('on')
  else if (day.bridged) cls.push('gap')
  return cls.join(' ')
}

export default Readiness
