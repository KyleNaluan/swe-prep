import { useMemo, type ReactNode } from 'react'
import { highlightCode } from './highlight'

// The structured lesson body renderer (issue #90 follow-on visual redesign). Before this, a
// lesson's whole taught content was one field - `statement`, plain text dropped into a single
// <p> - which is why the captain's feedback read it as "one block of continuous text [...]
// only takes up one column". This component renders the backend's new `Lesson.body` blocks
// (see backend `LessonBlock`/`LessonView.BlockView`) with W3Schools/GfG-like rhythm: heading,
// short prose, a standout example, repeat. `Lesson.tsx` falls back to the old single-paragraph
// rendering when `body` is empty (every real lesson today) - this component only ever sees a
// non-empty, already-validated block list.

export type CalloutStyle = 'NOTE' | 'TIP' | 'WARNING'

export type LessonBlockData =
  | { kind: 'heading'; level: number; text: string }
  | { kind: 'paragraph'; text: string }
  | { kind: 'example'; language: string; code: string; caption?: string; output?: string }
  | { kind: 'callout'; style: CalloutStyle; text: string }
  | { kind: 'list'; ordered: boolean; items: string[] }
  | { kind: 'table'; headers: string[]; rows: string[][] }

const CALLOUT_LABEL: Record<CalloutStyle, string> = {
  NOTE: 'Note',
  TIP: 'Tip',
  WARNING: 'Warning',
}

// Inline `code` spans (markdown-style backticks) inside prose - deliberately not its own
// block kind (see backend LessonBlock's javadoc): parsed here at render time so an author can
// mention a variable or function name without breaking a sentence into separate blocks.
function renderInline(text: string): ReactNode[] {
  return text.split(/(`[^`]+`)/g).map((part, i) =>
    part.startsWith('`') && part.endsWith('`') && part.length > 1 ? (
      <code key={i}>{part.slice(1, -1)}</code>
    ) : (
      part
    ),
  )
}

function LessonBody({ blocks }: { blocks: LessonBlockData[] }) {
  return (
    <div className="lesson-body">
      {blocks.map((block, i) => (
        <LessonBlockView key={i} block={block} />
      ))}
    </div>
  )
}

function LessonBlockView({ block }: { block: LessonBlockData }) {
  switch (block.kind) {
    case 'heading': {
      const Tag = block.level === 3 ? 'h3' : 'h2'
      return <Tag className="lesson-heading">{block.text}</Tag>
    }
    case 'paragraph':
      return <p className="lesson-p">{renderInline(block.text)}</p>
    case 'example':
      return <LessonExample block={block} />
    case 'callout':
      return (
        <div className={`callout callout-${block.style.toLowerCase()}`}>
          <span className="callout-label">{CALLOUT_LABEL[block.style]}</span>
          <p>{renderInline(block.text)}</p>
        </div>
      )
    case 'list': {
      const Tag = block.ordered ? 'ol' : 'ul'
      return (
        <Tag className="lesson-list">
          {block.items.map((item, i) => (
            <li key={i}>{renderInline(item)}</li>
          ))}
        </Tag>
      )
    }
    case 'table':
      return (
        <div className="lesson-table-wrap">
          <table className="lesson-table">
            <thead>
              <tr>
                {block.headers.map((header, i) => (
                  <th key={i}>{renderInline(header)}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {block.rows.map((row, i) => (
                <tr key={i}>
                  {row.map((cell, j) => (
                    <td key={j}>{renderInline(cell)}</td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )
    default:
      // Forward-compat: a block kind this build does not know about (the backend's sealed
      // hierarchy grew a case this frontend has not shipped yet) is skipped, never a crash.
      return null
  }
}

// A standout code example (requirement: "examples that stand out") - its own background and
// border accent (App.css `.example`), never blended into surrounding prose. `caption` names
// what the example shows and `output` shows what it prints/returns; either, both or neither
// may be present.
function LessonExample({ block }: { block: Extract<LessonBlockData, { kind: 'example' }> }) {
  const html = useMemo(() => highlightCode(block.code, block.language), [block.code, block.language])
  return (
    <div className="example">
      {block.caption && <div className="example-caption">{block.caption}</div>}
      <pre className="example-code">
        {/* Prism HTML-escapes every text token before wrapping it in a `token` span, so this
            is safe to inject even though the source is technically author-controlled content
            rather than a trusted internal constant (see highlight.ts). */}
        <code className={`language-${block.language}`} dangerouslySetInnerHTML={{ __html: html }} />
      </pre>
      {block.output && (
        <div className="example-output">
          <span className="example-output-label">Output</span>
          <pre>{block.output}</pre>
        </div>
      )}
    </div>
  )
}

export default LessonBody
