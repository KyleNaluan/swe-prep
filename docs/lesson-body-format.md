# Lesson body format (issue #90 follow-on visual redesign)

This is the spec for the structured lesson body added by the visual-redesign follow-on:
headings, prose, standout examples, callouts, lists and tables, replacing a lesson's single
plain-text `statement` blob with something a W3Schools/GfG-style renderer can lay out with
real rhythm.

**This file lives in the engine repo (`swe-prep`) because content is private and this repo
cannot touch it.** It is written to be dropped straight into `swe-prep-content/README.md`,
as a new `## Lesson body blocks` section right after the existing `## Lesson format` section,
by whoever picks up the content-repo follow-up (restructuring real lessons against this
format is explicitly out of scope for the engine change - see the engine PR). The backend's
own authoritative parser is `LessonParser`/`LessonBlock` (`backend/src/main/java/com/sweprep/
backend/content/LessonParser.java`, `.../exercise/LessonBlock.java`) - if this file and that
code ever disagree, the code wins and this file is stale.

## Why a new field, not a Markdown parser

The lesson `statement` field was already documented as "Markdown-friendly plain text", but
nothing in the app ever parsed it as Markdown - it rendered as one literal paragraph, which
is exactly why a lesson read as "one block of continuous text" no matter how long it was.
Rather than bolting a Markdown parser onto that field (and inheriting Markdown's ambiguity
about custom block types like a "standout example" or a "tip" callout), the lesson format
gained one more optional field - `body` - shaped the same way every other sealed-hierarchy
field in this format already is (`response`, `grading`, a complexity generator argument):
an array of objects, each with its own `"kind"` discriminator.

## `body`: optional, additive, legacy-safe

```jsonc
{
  "kind": "lesson",
  "id": "concept-message-queue",
  "title": "Message queues, and when to reach for one",
  "statement": "…",                        // still required; see below
  "domain": "fundamentals",
  "topics": ["messaging", "architecture"],
  "difficulty": "EASY",
  "checks": ["mq-when-to-use", "mq-vs-direct-call"],

  // Optional. An ordered list of structured body blocks - see "Block kinds" below.
  // Absent or an empty array means the lesson has no structured blocks yet; the app
  // then falls back to rendering `statement` as a single paragraph, exactly as it
  // always has. A lesson is never required to carry `body`.
  "body": [
    { "kind": "heading", "level": 2, "text": "…" },
    { "kind": "paragraph", "text": "…" }
  ],

  "family": ["BACKEND"],
  "stability": "VOLATILE",
  "reviewed": "2026-08-07"
}
```

`statement` stays required even on a lesson that also carries `body` - it is the lesson's
short plain-text description (used by any future search/preview surface that doesn't want to
walk the block list), not a subset of `body`. Restructuring a lesson's prose into `body`
blocks is a content-authoring act: the loader never derives one from the other.

## Block kinds

Every block is an object with a `"kind"` discriminator, the same shape `response`/`grading`
already use. A block's own text fields (`text`, a list `item`, a table cell) may contain
inline `` `code` `` spans (single backticks around a short span) - parsed by the renderer at
display time, not its own block kind, so a sentence can mention a variable or function name
without being split into multiple blocks.

**`heading`** - a section heading. `level` is optional (default `2`, an `<h2>`); the only
other allowed value is `3` (an `<h3>`) - a block-level heading can never claim `<h1>`, which
is the lesson's own title.

```json
{ "kind": "heading", "level": 2, "text": "How a lookup works" }
```

**`paragraph`** - a short paragraph of prose.

```json
{ "kind": "paragraph", "text": "A hash map applies a hash function to the key..." }
```

**`example`** - a standout code example, rendered in its own panel with syntax highlighting.
`language` drives highlighting (see the engine PR for the supported set - anything
unrecognized still renders, just uncolored). `caption` and `output` are each independently
optional: a caption names what the example shows, an output shows what it prints or returns.

```json
{
  "kind": "example",
  "language": "java",
  "code": "Map<String, Integer> counts = new HashMap<>();\ncounts.put(\"apple\", 3);\ncounts.get(\"apple\");",
  "caption": "Average-case O(1) lookup",
  "output": "3"
}
```

**`callout`** - a note/tip/warning aside, visually distinct from surrounding prose.
`style` is one of `NOTE`, `TIP`, `WARNING` (uppercase, matching every other enum-valued field
in this format - `difficulty`, `stability`, etc.).

```json
{
  "kind": "callout",
  "style": "WARNING",
  "text": "A poor hash function or an adversarial key set can collide many keys into one bucket, degrading lookup to O(n)."
}
```

**`list`** - a bullet or numbered list. `ordered` selects `<ol>` vs `<ul>`; `items` is a
non-empty array of strings, each of which may carry inline code spans.

```json
{
  "kind": "list",
  "ordered": false,
  "items": [
    "Each entry stores the key, the value, and a link to the next entry in its bucket's chain.",
    "The bucket array itself is resized once the map gets too full."
  ]
}
```

**`table`** - a simple data table: a header row plus zero or more data rows, each the same
width as the header. There is no column type or alignment - a richer table shape is a later
block kind, not a field bolted onto this one.

```json
{
  "kind": "table",
  "headers": ["Operation", "Average", "Worst case"],
  "rows": [
    ["get", "O(1)", "O(n)"],
    ["put", "O(1) amortized", "O(n)"]
  ]
}
```

## Worked example: a lesson with a structured body

```json
{
  "kind": "lesson",
  "id": "concept-caching",
  "title": "Caching: trading staleness for speed",
  "statement": "A cache trades a small chance of staleness for a large speed win.",
  "domain": "fundamentals",
  "topics": ["caching"],
  "difficulty": "EASY",
  "checks": [],
  "body": [
    { "kind": "heading", "level": 2, "text": "Cache-aside" },
    {
      "kind": "paragraph",
      "text": "Read through the cache first; on a miss, load from the source and populate it."
    },
    {
      "kind": "example",
      "language": "python",
      "code": "value = cache.get(key)\nif value is None:\n    value = db.load(key)\n    cache.set(key, value)",
      "caption": "The cache-aside pattern",
      "output": "value"
    },
    {
      "kind": "callout",
      "style": "TIP",
      "text": "Set a TTL so a stale entry cannot live forever."
    },
    {
      "kind": "list",
      "ordered": true,
      "items": ["Check the cache.", "On a miss, load from the source.", "Populate the cache."]
    },
    {
      "kind": "table",
      "headers": ["Strategy", "Consistency"],
      "rows": [
        ["Write-through", "Strong"],
        ["Write-behind", "Eventual"]
      ]
    }
  ]
}
```

This is also the synthetic fixture `Fixtures.lessonWithStructuredBody()` (backend
`testsupport` package, not real content) that the engine's own tests grade the format
against.
