import { describe, it, expect } from 'vitest'
import { DEFAULT_CONFIG } from './types'

describe('DEFAULT_CONFIG', () => {
  it('has 12 columns', () => {
    expect(DEFAULT_CONFIG.columns).toBe(12)
  })

  it('has 60px cell height', () => {
    expect(DEFAULT_CONFIG.cellHeight).toBe(60)
  })

  it('has 8px gap', () => {
    expect(DEFAULT_CONFIG.gap).toBe(8)
  })
})
