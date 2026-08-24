// Single source for the app's visible display name - distinct from the repo name, which
// stays "swe-prep" (the repo is not being renamed, just the product-facing label). The
// captain is still choosing the final name, so changing it is meant to be a one-line
// edit here, not a find-and-replace across the app.
//
// Used by the header brand (Session.tsx) and, at runtime, to set the browser tab title
// (main.tsx). Vite's index.html is static and cannot import this module, so its
// placeholder <title> is kept in sync by hand and is immediately overwritten by
// main.tsx on load - nothing user-visible may hardcode the old or new name anywhere else.
//
// "Re-solve" (round 3, captain's decision): hyphenated exactly like that, on purpose -
// it is meant to read as both "re-solve" (do it again) and "resolve" (see it through).
export const APP_NAME = 'Re-solve'
