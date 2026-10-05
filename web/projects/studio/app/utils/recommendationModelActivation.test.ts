import { describe, expect, it } from 'vitest'
import {
  pinnedRecommendationModelVersion,
  withPinnedRecommendationModelVersion,
} from './recommendationModelActivation'

describe('recommendation model activation', () => {
  it('reads only positive safe integer versions', () => {
    expect(pinnedRecommendationModelVersion({ modelVersion: 3 })).toBe(3)
    expect(pinnedRecommendationModelVersion({ modelVersion: 0 })).toBeNull()
    expect(pinnedRecommendationModelVersion({ modelVersion: '3' })).toBeNull()
    expect(pinnedRecommendationModelVersion(null)).toBeNull()
  })

  it('pins a version without dropping other serving configuration', () => {
    expect(withPinnedRecommendationModelVersion({ url: 'http://tf-serving', timeoutSeconds: 5 }, 7)).toEqual({
      url: 'http://tf-serving',
      timeoutSeconds: 5,
      modelVersion: 7,
    })
  })

  it('removes the pin to restore automatic latest-version activation', () => {
    expect(withPinnedRecommendationModelVersion({ modelVersion: 4, timeoutSeconds: 5 }, null)).toEqual({
      timeoutSeconds: 5,
    })
  })
})
