import Prism from 'prismjs'
import 'prismjs/components/prism-clike'
import 'prismjs/components/prism-java'
import 'prismjs/components/prism-python'
import 'prismjs/components/prism-sql'
import 'prismjs/components/prism-javascript'
import 'prismjs/components/prism-typescript'
import 'prismjs/components/prism-json'
import 'prismjs/components/prism-bash'

// Static syntax highlighting for lesson examples (issue #90 follow-on visual redesign).
// Monaco is the app's editor for *submitting* code, but a lesson example is never edited or
// run - it only needs a tokenizer to color it, and mounting a full Monaco instance per
// example would be a heavy, interactive-feeling widget for text the reader only looks at.
// Prism's core is ~2KB plus a small grammar file per language (all bundled here, no CDN or
// runtime fetch), and it is used purely as a tokenizer: `Prism.highlight` returns HTML built
// from Prism's own `token`/`token-<type>` classes, which App.css styles from this app's own
// Direction C design tokens (see the `.token.*` rules) rather than importing any of Prism's
// bundled themes - so a highlighted example fits the app's light/dark palette instead of
// fighting a library theme the way a themed stylesheet would.

// A content author writes the language a reader would recognize ("js", "py"); this maps
// that to the Prism grammar name it actually registers under.
const LANGUAGE_ALIASES: Record<string, string> = {
  js: 'javascript',
  jsx: 'javascript',
  ts: 'typescript',
  tsx: 'typescript',
  py: 'python',
  sh: 'bash',
  shell: 'bash',
}

/**
 * Highlights `code` for `language` as an HTML string. Falls back to plain HTML-escaped text
 * for a language Prism has no grammar for (or a typo) - an example never fails to render for
 * want of highlighting, it just renders uncolored.
 */
export function highlightCode(code: string, language: string): string {
  const grammarName = LANGUAGE_ALIASES[language.toLowerCase()] ?? language.toLowerCase()
  const grammar = Prism.languages[grammarName]
  if (!grammar) return escapeHtml(code)
  return Prism.highlight(code, grammar, grammarName)
}

function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
