import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import ThemeToggle from './ThemeToggle'

// Direct regression coverage for the cycle order and persistence the demo's
// `themeBtn` (direction-c.html) promised: system -> light -> dark -> system,
// applied to `data-theme` (the same attribute App.css's palette already keys off)
// and written to localStorage so a reload picks it back up.
describe('ThemeToggle', () => {
  beforeEach(() => {
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  afterEach(() => {
    cleanup()
    localStorage.clear()
    document.documentElement.removeAttribute('data-theme')
  })

  it('cycles system -> light -> dark -> system, updating data-theme and localStorage', () => {
    render(<ThemeToggle />)
    const button = screen.getByRole('button')

    expect(document.documentElement.getAttribute('data-theme')).toBeNull()

    fireEvent.click(button)
    expect(document.documentElement.getAttribute('data-theme')).toBe('light')
    expect(localStorage.getItem('sweprep-theme')).toBe('light')

    fireEvent.click(button)
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(localStorage.getItem('sweprep-theme')).toBe('dark')

    fireEvent.click(button)
    expect(document.documentElement.getAttribute('data-theme')).toBeNull()
    expect(localStorage.getItem('sweprep-theme')).toBe('system')
  })

  it('initializes from a stored choice rather than always starting at system', () => {
    localStorage.setItem('sweprep-theme', 'dark')
    render(<ThemeToggle />)

    expect(document.documentElement.getAttribute('data-theme')).toBe('dark')
    expect(screen.getByRole('button').title).toContain('Dark')
  })
})
