import { useCallback, useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'

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

type Progress = { achieved: number; total: number }
type FamilyReadiness = { family: string; checksToCriterion: Progress; solvedCold: Progress }
type ReadinessSummary = {
  checksToCriterion: Progress
  solvedCold: Progress
  conceptsCovered: Progress
  selfCheckExplainedCount: number
  families: FamilyReadiness[]
}

const ALWAYS_ACTIVE = new Set(['CORE', 'PROFESSIONAL'])

const FAMILY_LABELS: Record<string, string> = {
  CORE: 'Core',
  PROFESSIONAL: 'Professional',
  BACKEND: 'Backend',
  FRONTEND: 'Frontend',
  DATA: 'Data',
  DEVOPS: 'DevOps',
  MOBILE: 'Mobile',
  SYSTEMS: 'Systems',
  AIML: 'AI/ML',
}

function familyLabel(family: string): string {
  return FAMILY_LABELS[family] ?? family
}

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
          note="Challenges solved with no hint taken and no failing case revealed."
        />
        <ReadinessCard
          label="Concepts covered"
          progress={summary.conceptsCovered}
          note="Lessons read - so reading counts as earned progress too."
        />
      </div>

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
  return (
    <div className="readiness-card">
      <h2>{label}</h2>
      <p className="readiness-count">
        {progress.achieved}/{progress.total}
      </p>
      <p className="readiness-note">{note}</p>
    </div>
  )
}

export default Readiness
