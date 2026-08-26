// Shared label formatting for TreeBrowser's Area/Pattern tiers. Split out of
// TreeBrowser.tsx (a component file) so a consumer's own dedicated content-page
// breadcrumb (Practice.tsx/Lesson.tsx) can format its Area/Pattern segments with the
// exact same labels the tree shows, without a second, driftable copy of the same
// word-casing rule, and without tripping the Fast Refresh only-export-components rule
// a component file gets from adding non-component exports.

export function defaultDomainLabel(domain: string): string {
  return domain
    .split(/[-_]/)
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

export function topicLabel(topic: string): string {
  return defaultDomainLabel(topic.replace(/-/g, ' '))
}

// The `.chipx` color tone for a difficulty value - shared by TreeBrowser's own grid
// cards/rich sidebar rows and Practice.tsx's top-bar difficulty chip (captain-approved
// full-screen redesign, issue: swe-practice-fs-build), so the same difficulty always
// reads the same color wherever it renders.
export function difficultyTone(difficulty: string): string {
  const upper = difficulty.toUpperCase()
  if (upper === 'EASY') return 'g'
  if (upper === 'HARD') return 'r'
  return 'a'
}
