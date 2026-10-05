export interface CoverageCounts {
  observationCount: number
  impressions: number
}

/** Strictly compares two coverage ratios against ten percentage points. */
export function coverageDifferenceExceeds(first: CoverageCounts, second: CoverageCounts): boolean {
  const values = [first.observationCount, first.impressions, second.observationCount, second.impressions]
  if (values.every((value) => Number.isSafeInteger(value) && value >= 0)) {
    const firstNumerator = BigInt(first.impressions > 0 ? first.observationCount : 0)
    const firstDenominator = BigInt(first.impressions > 0 ? first.impressions : 1)
    const secondNumerator = BigInt(second.impressions > 0 ? second.observationCount : 0)
    const secondDenominator = BigInt(second.impressions > 0 ? second.impressions : 1)
    const rawDifference = firstNumerator * secondDenominator - secondNumerator * firstDenominator
    const absoluteDifference = rawDifference < 0n ? -rawDifference : rawDifference
    return absoluteDifference * 10n > firstDenominator * secondDenominator
  }

  const firstCoverage = first.impressions > 0 ? first.observationCount / first.impressions : 0
  const secondCoverage = second.impressions > 0 ? second.observationCount / second.impressions : 0
  return Math.abs(firstCoverage - secondCoverage) > 0.1 + Number.EPSILON * 8
}

export function hasCoverageImbalance(rows: CoverageCounts[]): boolean {
  return rows.some((first, index) =>
    rows.slice(index + 1).some((second) => coverageDifferenceExceeds(first, second)),
  )
}
