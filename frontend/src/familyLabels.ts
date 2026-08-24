// Shared display labels for the role-family taxonomy (design revision t3 section 2),
// used by Readiness's per-family breakdown and the Practice/Learn family filter group
// (issue #90) alike - one map, so a label never drifts between the two surfaces.
export const FAMILY_LABELS: Record<string, string> = {
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

export function familyLabel(family: string): string {
  return FAMILY_LABELS[family] ?? family
}
