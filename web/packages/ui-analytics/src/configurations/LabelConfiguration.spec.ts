import { describe, it, expect } from 'vitest'
import { LabelConfiguration } from './LabelConfiguration'

describe('LabelConfiguration', () => {
  it('has type LABEL', () => {
    expect(new LabelConfiguration().type).toBe('LABEL')
  })

  it('has sensible defaults', () => {
    const c = new LabelConfiguration()
    expect(c.label).toBe('')
    expect(c.fontSize).toBe('14px')
    expect(c.fontWeight).toBe('400')
    expect(c.alignment).toBe('left')
  })

  it('is always ready', () => {
    expect(new LabelConfiguration().isReady).toBe(true)
  })

  it('loads configuration', () => {
    const c = new LabelConfiguration()
    c.loadConfiguration({ label: 'Hello', fontSize: '20px', fontWeight: '700', alignment: 'center' })
    expect(c.label).toBe('Hello')
    expect(c.fontSize).toBe('20px')
    expect(c.fontWeight).toBe('700')
    expect(c.alignment).toBe('center')
  })

  it('uses defaults for missing config values', () => {
    const c = new LabelConfiguration()
    c.loadConfiguration({})
    expect(c.label).toBe('')
    expect(c.fontSize).toBe('14px')
  })

  it('does not throw on null config', () => {
    const c = new LabelConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.label).toBe('')
  })

  it('round-trips through getConfiguration', () => {
    const c = new LabelConfiguration()
    c.loadConfiguration({ label: 'Test', fontSize: '16px', fontWeight: '600', alignment: 'right' })
    const out = c.getConfiguration()
    expect(out).toEqual({ label: 'Test', fontSize: '16px', fontWeight: '600', alignment: 'right' })
  })
})
