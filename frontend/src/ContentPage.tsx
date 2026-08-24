import type { ReactNode } from 'react'

// The dedicated content page shell Practice and Learn both use - carrying Direction
// A's "three panes, drill left to right, breadcrumb above" pattern into Direction C's
// shipped design language (see AGENTS.md's visual-redesign section). Deliberately just
// the shell: a crumb trail plus whatever the caller renders as the body, so it composes
// with the lesson-body redesign happening in parallel (a separate task) rather than
// needing to know anything about what the body looks like inside.

export type Crumb = {
  label: string
  // Omitted on the trailing segment - the current page names itself, it does not
  // link to itself.
  onClick?: () => void
}

function ContentPage({ crumbs, children }: { crumbs: Crumb[]; children: ReactNode }) {
  return (
    <div className="content-page">
      <nav className="content-breadcrumb" aria-label="Breadcrumb">
        {crumbs.map((crumb, index) => (
          <span className="crumb-segment" key={index}>
            {crumb.onClick ? (
              <button type="button" className="crumb-link" onClick={crumb.onClick}>
                {crumb.label}
              </button>
            ) : (
              <span className="crumb-current" aria-current="page">
                {crumb.label}
              </span>
            )}
            {index < crumbs.length - 1 && (
              <span className="crumb-sep" aria-hidden="true">
                ›
              </span>
            )}
          </span>
        ))}
      </nav>
      <div className="content-page-body">{children}</div>
    </div>
  )
}

export default ContentPage
