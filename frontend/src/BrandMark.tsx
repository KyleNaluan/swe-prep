// The brand mark (captain's refinement, round 3): a loop of two arrows going in a
// circle - the "re-" of Re-solve, coming back around - with a compact code glyph
// hinted in the middle. Two identical arc strokes (one rotated 180° from the other,
// so only one path is hand-authored) each end in a small triangular arrowhead.
// `currentColor` so it inherits C's indigo wherever it is placed, in both palettes.
function BrandMark({ size = 20 }: { size?: number }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      focusable="false"
    >
      <g stroke="currentColor" strokeWidth="2.6" strokeLinecap="round">
        <path d="M 25.4 12.6 A 10 10 0 1 0 22 22" />
        <path d="M 25.4 12.6 A 10 10 0 1 0 22 22" transform="rotate(180 16 16)" />
      </g>
      <g fill="currentColor">
        <path d="M25.4 12.6 22.7 9.4 21.2 13.9z" />
        <path d="M6.6 19.4 9.3 22.6 10.8 18.1z" />
      </g>
      <text
        x="16"
        y="20.5"
        textAnchor="middle"
        fontFamily="var(--mono, ui-monospace, monospace)"
        fontSize="11"
        fontWeight="700"
        fill="currentColor"
      >
        {'</>'}
      </text>
    </svg>
  )
}

export default BrandMark
