import { describe, expect, it } from 'vitest'
import { coverageDifferenceExceeds, hasCoverageImbalance } from './experimentResultMath'

describe('experiment result coverage math', () => {
  it('accepts exact and below-boundary ten-point gaps', () => {
    expect(coverageDifferenceExceeds(
      { observationCount: 80, impressions: 100 },
      { observationCount: 70, impressions: 100 },
    )).toBe(false)
    expect(coverageDifferenceExceeds(
      { observationCount: 799, impressions: 1000 },
      { observationCount: 700, impressions: 1000 },
    )).toBe(false)
  })

  it('rejects above-boundary gaps including a zero-impression arm', () => {
    expect(coverageDifferenceExceeds(
      { observationCount: 801, impressions: 1000 },
      { observationCount: 700, impressions: 1000 },
    )).toBe(true)
    expect(coverageDifferenceExceeds(
      { observationCount: 0, impressions: 0 },
      { observationCount: 11, impressions: 100 },
    )).toBe(true)
  })

  it('checks every pair in a multi-arm result', () => {
    expect(hasCoverageImbalance([
      { observationCount: 80, impressions: 100 },
      { observationCount: 75, impressions: 100 },
      { observationCount: 69, impressions: 100 },
    ])).toBe(true)
  })
})
