import { useCallback, useEffect, useState } from 'react'
import { apiFetch, errorMessage } from './api'

// The role filter the user controls (issue #40). The user picks a named role - "Backend",
// "Full-stack + AI/ML" - not a checklist of family tags; the server expands the preset to a
// family set and that choice is what the required warm-up and auto-seeding then draw from.
//
// It deliberately does not hide anything: every family stays reachable through the Practice and
// Learn tabs whatever role is active. This control only shapes the daily core, which is the whole
// point of the filter - the habit trains the chosen roles. Reading an inactive-family lesson still
// pulls that one concept's checks in, and switching to a narrower role never rips already-due
// reviews out of the warm-up; both are enforced server-side.

type Preset = { id: string; label: string; families: string[] }
type RoleStatus = {
  presets: Preset[]
  activeFamilies: string[]
  currentPreset: string | null
  chosen: boolean
}

function RolePicker({ onChange }: { onChange?: () => void }) {
  const [status, setStatus] = useState<RoleStatus | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

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

  const currentLabel =
    status.presets.find((p) => p.id === status.currentPreset)?.label ??
    (status.chosen ? 'Custom' : 'Everything')

  return (
    <label className="role-picker">
      <span className="role-label">Focus</span>
      <select
        aria-label="Role focus"
        value={status.currentPreset ?? ''}
        disabled={saving}
        onChange={(e) => select(e.target.value)}
      >
        {/* An unset user matches "Everything", so no separate placeholder option is needed. */}
        {status.presets.map((preset) => (
          <option key={preset.id} value={preset.id}>
            {preset.label}
          </option>
        ))}
      </select>
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
