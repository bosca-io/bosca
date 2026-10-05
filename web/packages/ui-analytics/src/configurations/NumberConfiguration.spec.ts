import { describe, it, expect } from 'vitest'
import { NumberConfiguration } from './NumberConfiguration'

describe('NumberConfiguration', () => {
  it('has type NUMBER', () => {
    expect(new NumberConfiguration().type).toBe('NUMBER')
  })

  it('is not ready without valueKey', () => {
    expect(new NumberConfiguration().isReady).toBe(false)
  })

  it('is ready with valueKey', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'revenue' })
    expect(c.isReady).toBe(true)
  })

  it('loads configuration with fields', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'revenue', fields: { revenue: { format: 'currency' } } })
    expect(c.valueKey).toBe('revenue')
    expect(c.fieldSettings.revenue.format).toBe('currency')
  })

  it('does not throw on null config', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.valueKey).toBeUndefined()
  })

  it('returns -- when no data', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'revenue' })
    expect(c.getNumberValue()).toBe('--')
  })

  it('returns -- when no valueKey', () => {
    expect(new NumberConfiguration().getNumberValue()).toBe('--')
  })

  it('formats plain number', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'count' })
    c.setData([{ count: 42 }])
    expect(c.getNumberValue()).toBe('42')
  })

  it('formats currency', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'amt', fields: { amt: { format: 'currency' } } })
    c.setData([{ amt: 1234.56 }])
    expect(c.getNumberValue()).toContain('1,234.56')
  })

  it('formats percent', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'rate', fields: { rate: { format: 'percent' } } })
    c.setData([{ rate: 0.85 }])
    expect(c.getNumberValue()).toContain('85')
  })

  it('returns string for non-numeric value', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'label' })
    c.setData([{ label: 'hello' }])
    expect(c.getNumberValue()).toBe('hello')
  })

  it('round-trips getConfiguration', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'x', fields: { x: { label: 'X' } } })
    const out = c.getConfiguration()
    expect(out.value).toBe('x')
    expect(out.fields).toBeDefined()
  })

  it('omits empty fields from config', () => {
    const c = new NumberConfiguration()
    c.loadConfiguration({ value: 'x' })
    expect(c.getConfiguration()).toEqual({ value: 'x' })
  })
})
