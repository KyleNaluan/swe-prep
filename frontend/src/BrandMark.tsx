// The brand mark: a loop of two arrows going in a circle - the "re-" of
// Re-solve, coming back around - with a code-bracket glyph (`<` `>`, drawn
// as bold filled chevrons rather than rendered text) hinted in the middle.
// This is a cleaned-up redraw of the original concept (captain feedback:
// the shipped version's near-full-circle arcs and tiny `</>` text glyph
// read as jumbled at small size), not a new design. Each arc now sweeps a
// clean ~120° with a real gap on both sides instead of ~290° nearly
// doubling into a solid ring, the arc strokes end in a flat (butt) cap so
// no round-cap bump pokes out past the arrowhead triangles, and the whole
// mark is scaled up within its viewBox to fill more of the frame.
// `currentColor` throughout so it inherits C's indigo wherever it is
// placed, in both palettes.
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
      <g transform="translate(16 16) scale(1.25) translate(-16 -16)">
        <g stroke="currentColor" strokeWidth="3.8" strokeLinecap="butt">
          <path d="M 23.79 11.5 A 9 9 0 0 0 8.21 11.5" />
          <path d="M 8.21 20.5 A 9 9 0 0 0 23.79 20.5" />
        </g>
        <g fill="currentColor">
          <path d="M6.46 14.53 6.51 8.44 11.71 11.44Z" />
          <path d="M25.54 17.47 25.49 23.56 20.29 20.56Z" />
        </g>
        <g fill="currentColor">
          <path d="M14.5 11.5 10 16 14.5 20.5 16.3 18.7 13.2 16 16.3 13.3Z" />
          <path d="M17.5 11.5 22 16 17.5 20.5 15.7 18.7 18.8 16 15.7 13.3Z" />
        </g>
      </g>
    </svg>
  )
}

export default BrandMark
