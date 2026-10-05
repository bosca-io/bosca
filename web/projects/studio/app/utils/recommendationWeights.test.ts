import { describe, expect, it } from 'vitest'
import { createRecommendationWeights, copyRecommendationWeights, describeTypePreference, validateRecommendationWeights } from './recommendationWeights'

describe('recommendation weights', () => {
  it.each([
    [0.8, 0.5, '20% higher'],
    [0, 1, '50% lower'],
    [1, 0, '100% higher'],
    [0.5, 0.5, 'Same content score'],
    [0, 0, 'Same content score'],
    [NaN, 0.5, 'Enter preferences'],
    [0.8, -1, 'Enter preferences'],
  ])('describes preference %s against default %s', (preference, defaultPreference, expected) => {
    expect(describeTypePreference(preference as number, defaultPreference as number)).toContain(expected)
  })

  it('round trips zero and keeps forms independent', () => {
    const first = createRecommendationWeights()
    first.defaultTypePreference = 0
    first.personalization = 0
    first.typePreferences = [{ type: ' Devotional ', weight: 0 }]
    const copied = copyRecommendationWeights(first)
    expect(copied.typePreferences).toEqual([{ type: 'devotional', weight: 0 }])
    expect(copied.defaultTypePreference).toBe(0)
    expect(copied.personalization).toBe(0)
    copied.typePreferences[0]!.weight = 0.9
    copied.similarity.collections = 1
    expect(first.typePreferences[0]!.weight).toBe(0)
    expect(first.similarity.collections).toBe(0.2)
    expect(createRecommendationWeights().defaultTypePreference).toBe(0.5)
  })

  it.each([-1, 1.1, NaN, Infinity])('rejects invalid weight %s', value => {
    const weights = createRecommendationWeights()
    weights.similarity.collections = value
    expect(validateRecommendationWeights(weights)).toBe('Weights must be numbers between 0 and 1.')
  })

  it('rejects duplicate normalized type preferences and blank keys', () => {
    const weights = createRecommendationWeights()
    weights.typePreferences = [{ type: 'Guide', weight: 0 }, { type: ' guide ', weight: 1 }]
    expect(validateRecommendationWeights(weights)).toContain('distinct types')
    weights.typePreferences = [{ type: ' ', weight: 0 }]
    expect(validateRecommendationWeights(weights)).toContain('needs an editorial type')
  })

  it('requires one positive similarity weight but permits zero behavioral influences', () => {
    const weights = createRecommendationWeights()
    for (const key of Object.keys(weights.similarity) as (keyof typeof weights.similarity)[]) weights.similarity[key] = 0
    expect(validateRecommendationWeights(weights)).toContain('At least one similarity')
    weights.similarity.type = 0.01
    weights.personalization = 0
    expect(validateRecommendationWeights(weights)).toBeNull()
  })
})
