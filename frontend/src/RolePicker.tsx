import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { apiFetch, errorMessage } from './api'
import { roleLabelFrom, type RoleStatus } from './role'

// The role filter the user controls (issue #40). The user picks a named role - "Backend",
// "Full-stack + AI/ML" - not a checklist of family tags; the server expands the preset to a
// family set and that choice is what the required warm-up and auto-seeding then draw from.
//
// It deliberately does not hide anything: every family stays reachable through the Practice and
// Learn tabs whatever role is active. This control only shapes the daily core, which is the whole
// point of the filter - the habit trains the chosen roles. Reading an inactive-family lesson still
// pulls that one concept's checks in, and switching to a narrower role never rips already-due
// reviews out of the warm-up; both are enforced server-side.
//
// Both effects of that choice used to be invisible in the UI (captain-reported, 2026-08-26):
// a hover/focus tooltip here names what the dropdown actually does, and Warmup.tsx (via
// `roleLabelFrom` in role.ts, so it reads the exact same status the dropdown does rather than
// a second copy) shows a quiet "Drawing from: <label>" line on the warm-up surface itself.

const TOOLTIP_ID = 'role-focus-tooltip'
// The tooltip's CSS anchors it flush to the dropdown's own right edge, which reads well on
// a wide header where that edge sits well inside the viewport - but the header re-wraps at
// narrow widths (see .session-header-controls's flex-wrap), and there the same dropdown can
// land close enough to the viewport's own left edge that a fixed-width tooltip anchored that
// way would spill past it. There is no pure-CSS anchor that knows the control's actual
// viewport position, so this measures the rendered tooltip and nudges it back on-screen -
// never touching the wide-layout case, where the natural position already fits and the
// computed shift is 0.
const TOOLTIP_VIEWPORT_MARGIN = 12

function RolePicker({ onChange }: { onChange?: () => void }) {
  const [status, setStatus] = useState<RoleStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  // The floating description shows on hover OR keyboard focus - tracked separately (not one
  // combined flag toggled by every handler) so a mouse leaving the control while it is still
  // keyboard-focused does not hide a tooltip that focus alone should keep open.
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const tooltipOpen = hovered || focused
  const tooltipElRef = useRef<HTMLSpanElement>(null)
  // A horizontal nudge applied only when the tooltip's natural (CSS-only) position would
  // clip past the viewport edge - see TOOLTIP_VIEWPORT_MARGIN above. Zero, and therefore a
  // no-op, on every layout where the natural position already fits.
  const [tooltipShift, setTooltipShift] = useState(0)

  useLayoutEffect(() => {
    if (!tooltipOpen) {
      setTooltipShift(0)
      return
    }
    const el = tooltipElRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    if (rect.left < TOOLTIP_VIEWPORT_MARGIN) {
      setTooltipShift(TOOLTIP_VIEWPORT_MARGIN - rect.left)
    } else if (rect.right > window.innerWidth - TOOLTIP_VIEWPORT_MARGIN) {
      setTooltipShift(window.innerWidth - TOOLTIP_VIEWPORT_MARGIN - rect.right)
    } else {
      setTooltipShift(0)
    }
  }, [tooltipOpen])

  const load = useCallback(() => {
    apiFetch('/api/role')
      .then(async (response) => {
        if (!response.ok) throw new Error(await errorMessage(response))
        return (await response.json()) as RoleStatus
      })
      .then(setStatus)
      .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
  }, [])

  useEffect(() => {
    load()
  }, [load])

  const select = useCallback(
    (presetId: string) => {
      setSaving(true)
      setError(null)
      apiFetch('/api/role', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preset: presetId }),
      })
        .then(async (response) => {
          if (!response.ok) throw new Error(await errorMessage(response))
          return (await response.json()) as RoleStatus
        })
        .then((updated) => {
          setStatus(updated)
          // The active set changed, so the warm-up must be rebuilt from the new families.
          onChange?.()
        })
        .catch((e: unknown) => setError(e instanceof Error ? e.message : String(e)))
        .finally(() => setSaving(false))
    },
    [onChange],
  )

  // The picker is secondary chrome; a failure to read it must never block the day. Show a quiet
  // note rather than an error wall, and render nothing until the first read lands.
  if (error && !status) {
    return (
      <span className="role-picker error" title={error}>
        Focus unavailable
      </span>
    )
  }
  if (!status) return null

  const currentLabel = roleLabelFrom(status)

  return (
    <label className="role-picker">
      <span className="role-label">Focus</span>
      <span
        className="role-field"
        onMouseEnter={() => setHovered(true)}
        onMouseLeave={() => setHovered(false)}
      >
        <select
          aria-label="Role focus"
          aria-describedby={TOOLTIP_ID}
          value={status.currentPreset ?? ''}
          disabled={saving}
          onChange={(e) => select(e.target.value)}
          onFocus={() => setFocused(true)}
          onBlur={() => setFocused(false)}
        >
          {/* An unset user matches "Everything", so no separate placeholder option is needed. */}
          {status.presets.map((preset) => (
            <option key={preset.id} value={preset.id}>
              {preset.label}
            </option>
          ))}
        </select>
        {/* Names what the dropdown actually does (captain-requested, 2026-08-26): the choice
            was already real server-side (WarmupService reads RoleService.activeFamilies) but
            nothing said so anywhere in the UI. `hidden` (not just a CSS hover rule) keeps this
            out of the accessibility tree until it is genuinely shown. */}
        <span
          className="role-tooltip"
          id={TOOLTIP_ID}
          role="tooltip"
          hidden={!tooltipOpen}
          ref={tooltipElRef}
          style={tooltipShift ? { transform: `translateX(${tooltipShift}px)` } : undefined}
        >
          Focus decides which topic families the daily warm-up and newly seeded items draw
          from. Everything stays browsable in Practice and Learn either way, and switching
          never drops a review that is already due.
        </span>
      </span>
      <span className="visually-hidden">Current focus: {currentLabel}</span>
      {/* A failed save leaves the select reverted to the persisted role; say so inline rather
          than let the change vanish silently. */}
      {error && (
        <span className="role-picker-error" role="alert" title={error}>
          Not saved
        </span>
      )}
    </label>
  )
}

export default RolePicker
