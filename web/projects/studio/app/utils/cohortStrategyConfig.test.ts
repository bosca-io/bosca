import { describe, it, expect } from 'vitest'
import { readCohortCaps, buildCohortConfiguration, DEFAULT_COHORT_CAP } from './cohortStrategyConfig'

describe('readCohortCaps', () => {
  it('reads valid caps', () => {
    expect(readCohortCaps({ perUserItemCap: 150, perSourceCap: 300 })).toEqual({ perUserItemCap: 150, perSourceCap: 300 })
  })

  it('defaults each missing field to 200', () => {
    expect(readCohortCaps({ perUserItemCap: 50 })).toEqual({ perUserItemCap: 50, perSourceCap: DEFAULT_COHORT_CAP })
    expect(readCohortCaps({})).toEqual({ perUserItemCap: 200, perSourceCap: 200 })
    expect(readCohortCaps(null)).toEqual({ perUserItemCap: 200, perSourceCap: 200 })
    expect(readCohortCaps(undefined)).toEqual({ perUserItemCap: 200, perSourceCap: 200 })
  })

  it('falls back to the default for invalid or non-positive values', () => {
    expect(readCohortCaps({ perUserItemCap: 0, perSourceCap: -5 })).toEqual({ perUserItemCap: 200, perSourceCap: 200 })
    expect(readCohortCaps({ perUserItemCap: 'oops', perSourceCap: null })).toEqual({ perUserItemCap: 200, perSourceCap: 200 })
  })

  it('coerces numeric strings and floors fractions', () => {
    expect(readCohortCaps({ perUserItemCap: '120', perSourceCap: 90.9 })).toEqual({ perUserItemCap: 120, perSourceCap: 90 })
  })
})

describe('buildCohortConfiguration', () => {
  it('sanitizes the caps into a storable object', () => {
    expect(buildCohortConfiguration({ perUserItemCap: 150, perSourceCap: 300 })).toEqual({ perUserItemCap: 150, perSourceCap: 300 })
    expect(buildCohortConfiguration({ perUserItemCap: 0, perSourceCap: 25 })).toEqual({ perUserItemCap: 200, perSourceCap: 25 })
  })
})
