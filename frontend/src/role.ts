// Shared with RolePicker.tsx and Warmup.tsx (issue #40's dropdown, made visible - captain-
// requested 2026-08-26) so the dropdown and the warm-up's quiet "Drawing from:" line always
// read the exact same /api/role status the exact same way, rather than two labelling rules
// that could drift apart. Split into its own module, not exported from RolePicker.tsx, to
// keep that file a components-only export (oxlint's react/only-export-components) - the same
// reason treeLabels.ts exists alongside TreeBrowser.tsx.

type Preset = { id: string; label: string; families: string[] }
export type RoleStatus = {
  presets: Preset[]
  activeFamilies: string[]
  currentPreset: string | null
  chosen: boolean
}

// The human label for the active focus - "Everything" when unset, "Custom" for a chosen but
// unnamed combination, else the matching preset's own label.
export function roleLabelFrom(status: RoleStatus): string {
  return (
    status.presets.find((p) => p.id === status.currentPreset)?.label ??
    (status.chosen ? 'Custom' : 'Everything')
  )
}
