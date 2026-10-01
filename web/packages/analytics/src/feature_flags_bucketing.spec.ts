import { describe, expect, test } from 'vitest'
import fixtureRaw from './fixtures/targeting_bucketing_fixture.json'

/**
 * TypeScript side of the cross-language bucketing parity fixture.
 * Loads the same `targeting_bucketing_fixture.json` that the Kotlin
 * `TargetingBucketingFixtureTest` consumes and runs a TypeScript
 * reimplementation of FNV-1a 64-bit + the sorted-weight bucket walk
 * against every row. If the two implementations ever disagree on
 * `(flagKey, salt, ruleId, identifier) → bucket`, one of them would
 * silently assign users to the wrong variation and corrupt experiment
 * results; the shared fixture makes any such drift a test failure.
 *
 * The browser SDK currently delegates all flag evaluation to the
 * server (via GraphQL) and does NOT do local bucketing. This spec
 * therefore tests the *algorithm in isolation* — when local TS
 * evaluation is added later, the same `bucketRollout` function below
 * is what it must use, so the parity fixture pins the contract on day
 * one rather than after the first incident.
 *
 * If you ever change the algorithm, regenerate the fixture's
 * `expectedHash` and `expectedVariation` fields ONCE (the existing
 * Kotlin test will tell you the new values), and update both this
 * spec and `TargetingBucketingFixtureTest.kt` in the same commit.
 */

// 64-bit math via BigInt because plain `number` only has 53 bits of
// precision. The Kotlin side uses signed `Long` arithmetic and masks
// the sign bit at the end; we mirror that here.
const FNV_OFFSET_BASIS = 0xcbf29ce484222325n
const FNV_PRIME = 0x100000001b3n
const U64_MASK = 0xffffffffffffffffn
const SIGN_MASK = 0x7fffffffffffffffn

function fnv1a64(input: string): bigint {
  let hash = FNV_OFFSET_BASIS
  const bytes = new TextEncoder().encode(input)
  for (let i = 0; i < bytes.length; i++) {
    hash ^= BigInt(bytes[i])
    hash = (hash * FNV_PRIME) & U64_MASK
  }
  return hash & SIGN_MASK
}

interface VariationWeight { variationKey: string, weight: number }

function bucketRollout(
  flagKey: string, salt: string, ruleId: string,
  variationWeights: VariationWeight[],
  identifier: string,
): string {
  const sorted = [...variationWeights].sort((a, b) =>
    a.variationKey < b.variationKey ? -1 : a.variationKey > b.variationKey ? 1 : 0,
  )
  // Sum into BigInt to mirror the Kotlin Long-sum overflow defense:
  // pathological weight sets whose Int sum would wrap must still
  // produce the same bucket on both sides.
  let totalWeight = 0n
  for (const vw of sorted) totalWeight += BigInt(vw.weight)
  if (totalWeight <= 0n) throw new Error('Total rollout weight must be positive')
  const hash = fnv1a64(`${flagKey}:${salt}:${ruleId}:${identifier}`)
  const bucket = hash % totalWeight
  let cumulative = 0n
  for (const vw of sorted) {
    cumulative += BigInt(vw.weight)
    if (bucket < cumulative) return vw.variationKey
  }
  return sorted[sorted.length - 1].variationKey
}

interface FixtureCase {
  flagKey: string
  salt: string
  ruleId: string
  identifier: string
  expectedVariation: string
  expectedHash?: number | string
}

interface FixtureSection {
  rolloutSorted: VariationWeight[]
  cases: FixtureCase[]
}

interface UniformitySection {
  rolloutSorted: VariationWeight[]
  flagKey: string
  salt: string
  ruleId: string
  identifierPattern: string
  n: number
  expectedDistribution: Record<string, number>
}

interface Fixture {
  twoVariation_50_50: FixtureSection
  threeVariation_1_2_3: FixtureSection
  uniformity: UniformitySection
}

const fixture = fixtureRaw as unknown as Fixture

describe('targeting bucketing parity fixture', () => {

  test('two-variation 50/50 split matches the fixture', () => {
    const section = fixture.twoVariation_50_50
    for (const c of section.cases) {
      const v = bucketRollout(c.flagKey, c.salt, c.ruleId, section.rolloutSorted, c.identifier)
      expect(v).toBe(c.expectedVariation)
    }
  })

  test('three-variation 1/2/3 split matches the fixture', () => {
    const section = fixture.threeVariation_1_2_3
    for (const c of section.cases) {
      const v = bucketRollout(c.flagKey, c.salt, c.ruleId, section.rolloutSorted, c.identifier)
      expect(v).toBe(c.expectedVariation)
    }
  })

  test('uniformity over 10000 ids matches the fixture distribution exactly', () => {
    const section = fixture.uniformity
    const counts: Record<string, number> = { control: 0, treatment: 0 }
    for (let i = 0; i < section.n; i++) {
      const id = `user-${String(i).padStart(5, '0')}`
      const v = bucketRollout(section.flagKey, section.salt, section.ruleId, section.rolloutSorted, id)
      counts[v] = (counts[v] || 0) + 1
    }
    expect(counts).toEqual(section.expectedDistribution)
  })

  test('bucketing is deterministic across repeated calls', () => {
    const rollout = [
      { variationKey: 'a', weight: 1 },
      { variationKey: 'b', weight: 1 },
      { variationKey: 'c', weight: 1 },
    ]
    const first = bucketRollout('flag', 'salt', 'rule', rollout, 'user-1')
    for (let i = 0; i < 100; i++) {
      expect(bucketRollout('flag', 'salt', 'rule', rollout, 'user-1')).toBe(first)
    }
  })

  // Mirror the Kotlin side's `require(totalWeight > 0L)` guard so a future
  // refactor of either implementation can't silently diverge on how
  // non-positive total weights are handled. Weights should always come from
  // the server in practice, but locking the contract down in tests keeps the
  // two bucketers honest when the fixture grows new cases.
  test('non-positive total weight throws', () => {
    expect(() =>
      bucketRollout('flag', 'salt', 'rule', [
        { variationKey: 'a', weight: 0 },
        { variationKey: 'b', weight: 0 },
      ], 'user-1'),
    ).toThrow(/positive/)

    expect(() =>
      bucketRollout('flag', 'salt', 'rule', [
        { variationKey: 'a', weight: 1 },
        { variationKey: 'b', weight: -1 },
      ], 'user-1'),
    ).toThrow(/positive/)
  })
})
