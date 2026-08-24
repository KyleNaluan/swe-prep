import '@testing-library/jest-dom/vitest'

// jsdom has no real layout/scrolling and logs a noisy "Not implemented" warning on
// every call to window.scrollTo - stub it out so content-page navigation (which
// scrolls on every browse<->content switch, see contentNav.ts) doesn't spam test
// output with a warning that reflects a jsdom gap, not an app bug.
window.scrollTo = () => {}
