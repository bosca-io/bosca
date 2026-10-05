import { describe, it, expect } from 'vitest'
import { TableConfiguration } from './TableConfiguration'

describe('TableConfiguration', () => {
  it('has type TABLE', () => {
    expect(new TableConfiguration().type).toBe('TABLE')
  })

  it('is always ready', () => {
    expect(new TableConfiguration().isReady).toBe(true)
  })

  it('loads columns as array', () => {
    const c = new TableConfiguration()
    c.loadConfiguration({ columns: ['name', 'email'] })
    expect(c.tableColumnKeys).toEqual(['name', 'email'])
  })

  it('loads single column as array', () => {
    const c = new TableConfiguration()
    c.loadConfiguration({ columns: 'name' })
    expect(c.tableColumnKeys).toEqual(['name'])
  })

  it('handles missing columns', () => {
    const c = new TableConfiguration()
    c.loadConfiguration({})
    expect(c.tableColumnKeys).toEqual([])
  })

  it('does not throw on null config', () => {
    const c = new TableConfiguration()
    c.loadConfiguration(null as unknown as Record<string, unknown>)
    expect(c.tableColumnKeys).toEqual([])
  })

  it('filters columns by selected keys', () => {
    const c = new TableConfiguration()
    c.loadConfiguration({ columns: ['name', 'email'] })
    const all = [
      { accessorKey: 'name' },
      { accessorKey: 'email' },
      { accessorKey: 'phone' },
    ]
    expect(c.getColumns(all)).toEqual([
      { accessorKey: 'name' },
      { accessorKey: 'email' },
    ])
  })

  it('returns all columns when none selected', () => {
    const c = new TableConfiguration()
    const all = [{ accessorKey: 'name' }, { accessorKey: 'email' }]
    expect(c.getColumns(all)).toEqual(all)
  })

  it('round-trips getConfiguration with columns', () => {
    const c = new TableConfiguration()
    c.loadConfiguration({ columns: ['a', 'b'] })
    expect(c.getConfiguration()).toEqual({ columns: ['a', 'b'] })
  })

  it('returns empty config when no columns or fields', () => {
    const c = new TableConfiguration()
    expect(c.getConfiguration()).toEqual({})
  })
})
