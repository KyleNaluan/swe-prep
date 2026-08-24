import { describe, expect, it } from 'vitest'
import { highlightCode } from './highlight'

describe('highlightCode (issue #90 follow-on lesson examples)', () => {
  it('tokenizes a recognized language into Prism token spans', () => {
    const html = highlightCode('def add(a, b):\n    return a + b', 'python')
    expect(html).toContain('token keyword')
    expect(html).toContain('def')
  })

  it('resolves a short-form alias to its Prism grammar', () => {
    const html = highlightCode('const x = 1', 'js')
    expect(html).toContain('token keyword')
  })

  it('falls back to escaped plain text for an unrecognized language rather than throwing', () => {
    const html = highlightCode('<not a real language>', 'brainfuck-but-not-really')
    expect(html).toBe('&lt;not a real language&gt;')
  })
})
