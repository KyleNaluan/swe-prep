import { useEffect, useState } from 'react'

// The completion moment's confetti (Direction C): "38 confetti pieces fall through the
// panel." Purely decorative - it renders once, animates via the CSS keyframe in
// App.css (which the app's existing `prefers-reduced-motion` rule already collapses to
// near-zero duration, so no separate reduced-motion branch is needed here), and
// unmounts itself when done. Never gates or delays anything else on screen.
const PIECES = 30
const COLORS = ['var(--primary)', 'var(--primary-ink)', 'var(--emerald)', 'var(--amber)', 'var(--coral)']

function Confetti() {
  const [visible, setVisible] = useState(true)

  useEffect(() => {
    const timer = window.setTimeout(() => setVisible(false), 1600)
    return () => window.clearTimeout(timer)
  }, [])

  if (!visible) return null

  return (
    <div className="confetti-field" aria-hidden="true">
      {Array.from({ length: PIECES }, (_, i) => {
        const left = 10 + Math.random() * 80
        const delay = Math.random() * 200
        const duration = 1100 + Math.random() * 700
        const drift = (Math.random() - 0.5) * 160
        const rotate = Math.random() * 720 - 360
        return (
          <span
            key={i}
            className="confetti"
            style={{
              left: `${left}%`,
              background: COLORS[i % COLORS.length],
              animationDelay: `${delay}ms`,
              animationDuration: `${duration}ms`,
              ['--drift' as string]: `${drift}px`,
              ['--rotate' as string]: `${rotate}deg`,
            }}
          />
        )
      })}
    </div>
  )
}

export default Confetti
