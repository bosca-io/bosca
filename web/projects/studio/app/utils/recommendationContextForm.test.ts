import { describe, expect, it } from 'vitest'
import {
  buildRecommendationContextInput,
  createRecommendationContextForm,
  DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS,
  recommendationContextToForm,
  validateRecommendationContextForm,
} from './recommendationContextForm'

describe('recommendation context form', () => {
  it('starts with the experience-safe metadata exclusions and collections enabled', () => {
    const form = createRecommendationContextForm()

    expect(form.metadata.excludedContentTypePrefixes).toEqual(DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS)
    expect(form.metadata.excludedContentTypePrefixes).not.toBe(DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS)
    expect(form.collectionsEnabled).toBe(true)
  })

  it('copies a saved context without sharing filter arrays', () => {
    const source = {
      type: 'images',
      name: 'Images',
      description: 'Image selection',
      contentFilter: {
        metadata: {
          includedContentTypePrefixes: ['image/'],
          excludedContentTypePrefixes: [],
          includedAttributeTypes: ['hero'],
          excludedAttributeTypes: [],
        },
        collections: null,
      },
    }

    const form = recommendationContextToForm(source)
    form.metadata.includedContentTypePrefixes.push('video/')

    expect(form.collectionsEnabled).toBe(false)
    expect(source.contentFilter.metadata.includedContentTypePrefixes).toEqual(['image/'])
  })

  it('normalizes context inputs and excludes collections with a null filter', () => {
    const form = createRecommendationContextForm()
    form.type = '  Image_Picker '
    form.name = ' Image picker '
    form.description = ' Select assets '
    form.metadata.includedContentTypePrefixes = [' image/ ', 'image/', '']
    form.collectionsEnabled = false

    expect(validateRecommendationContextForm(form)).toBeNull()
    expect(buildRecommendationContextInput(form)).toEqual({
      type: 'image_picker',
      name: 'Image picker',
      description: 'Select assets',
      weights: form.weights,
      contentFilter: {
        metadata: {
          includedContentTypePrefixes: ['image/'],
          excludedContentTypePrefixes: DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS,
          includedAttributeTypes: [],
          excludedAttributeTypes: [],
        },
        collections: null,
      },
    })
  })

  it('includes all collection facets when collections are enabled', () => {
    const form = createRecommendationContextForm()
    form.type = 'series'
    form.name = 'Series'
    form.collections.includedTypes = ['STANDARD']
    form.collections.excludedTypes = ['FOLDER']
    form.collections.includedAttributeTypes = ['series']
    form.collections.excludedAttributeTypes = ['archive']

    expect(buildRecommendationContextInput(form).contentFilter?.collections).toEqual({
      includedTypes: ['STANDARD'],
      excludedTypes: ['FOLDER'],
      includedAttributeTypes: ['series'],
      excludedAttributeTypes: ['archive'],
    })
  })

  it.each([
    { name: '', type: 'default', error: 'Name is required.' },
    { name: 'Default', type: '', error: 'Type is required.' },
    { name: 'Default', type: 'bad type', error: 'Type must contain only letters, numbers, hyphens, or underscores.' },
  ])('validates $error', ({ name, type, error }) => {
    const form = createRecommendationContextForm()
    form.name = name
    form.type = type

    expect(validateRecommendationContextForm(form)).toBe(error)
  })
})
