import { useEffect, useState } from 'react'
import { fetchSessionHistory, type DayHistory } from './sessionHistory'

// The Direction C graft: "the last thirty days as a row of bars... light indigo for a
// warm-up day, deep indigo for a warm-up plus a solved challenge, a hatched bar for a
// missed day the next day's double session bridged." A picture of day_completion, not
// a score - the same honest-record principle (issue #7) as everything else here.
//
// `dayComplete` is the session's own already-known truth for today (issue #19's
// status), applied locally to today's cell rather than waiting on a refetch: the
// history endpoint was read once on mount, so without this the ribbon would show
// today as still-open for the rest of the sitting even the instant after the warm-up
// completion moment lands.
function DayRibbon({ dayComplete = false }: { dayComplete?: boolean }) {
  const [days, setDays] = useState<DayHistory[] | null>(null)

  useEffect(() => {
    let cancelled = false
    fetchSessionHistory()
      .then((history) => {
        if (!cancelled) setDays(history.slice(-30))
      })
      .catch(() => {
        // The ribbon is a secondary flourish; a failure here must never block the warm-up.
      })
    return () => {
      cancelled = true
    }
  }, [])

  if (!days || days.length === 0) return null

  const todayIndex = days.length - 1
  const withToday = dayComplete
    ? days.map((day, i) => (i === todayIndex ? { ...day, completed: true } : day))
    : days
  const today = withToday[todayIndex]

  return (
    <div className="card ribbon">
      <h2>The last thirty days</h2>
      <p className="sub">
        A record of days you showed up, kept because it is true - not something you spend.
      </p>
      <div className="strip">
        {withToday.map((day) => (
          <span
            key={day.date}
            className={`day ${dayClass(day, day === today)}`}
            title={dayTitle(day, day === today)}
          />
        ))}
      </div>
      <div className="legend">
        <span>
          <i style={{ background: 'var(--primary)', opacity: 0.3 }} /> warm-up done
        </span>
        <span>
          <i
            style={{ background: 'linear-gradient(180deg, var(--primary), var(--primary-ink))' }}
          />{' '}
          warm-up plus a challenge
        </span>
        <span>
          <i
            style={{
              background:
                'repeating-linear-gradient(45deg, var(--surface3) 0 3px, transparent 3px 6px)',
            }}
          />{' '}
          missed, bridged by the next day's double
        </span>
      </div>
    </div>
  )
}

function dayClass(day: DayHistory, isToday: boolean): string {
  if (isToday) return day.completed ? 'today lit' : 'today'
  if (day.bridged) return 'gap'
  if (day.doubleSession) return 'dbl'
  if (day.completed) return 'on'
  return ''
}

function dayTitle(day: DayHistory, isToday: boolean): string {
  const label = isToday ? 'Today' : day.date
  if (day.doubleSession) return `${label}: warm-up plus a solved challenge`
  if (day.completed) return `${label}: warm-up done`
  if (day.bridged) return `${label}: missed, bridged by the next day's double session`
  return `${label}: not practiced`
}

export default DayRibbon
