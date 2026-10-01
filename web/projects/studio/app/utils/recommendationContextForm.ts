import type { RecommendationContextInput } from '~/types/graphql'
import {
  copyRecommendationWeights,
  createRecommendationWeights,
  validateRecommendationWeights,
  type RecommendationWeightValues,
} from './recommendationWeights'

export const DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS = [
  'image/',
  'video/',
  'audio/',
  'font/',
  'model/',
  'application/octet-stream',
]

export interface RecommendationContextFilterValues {
  includedContentTypePrefixes: string[]
  excludedContentTypePrefixes: string[]
  includedAttributeTypes: string[]
  excludedAttributeTypes: string[]
}

export interface RecommendationCollectionFilterValues {
  includedTypes: string[]
  excludedTypes: string[]
  includedAttributeTypes: string[]
  excludedAttributeTypes: string[]
}

export interface RecommendationContextFormValues {
  weights: RecommendationWeightValues
  type: string
  name: string
  description: string
  metadata: RecommendationContextFilterValues
  collectionsEnabled: boolean
  collections: RecommendationCollectionFilterValues
}

export interface RecommendationContextFormSource {
  weights?: RecommendationWeightValues
  type: string
  name: string
  description: string
  contentFilter: {
    metadata: RecommendationContextFilterValues
    collections?: RecommendationCollectionFilterValues | null
  }
}

export function createRecommendationContextForm(): RecommendationContextFormValues {
  return {
    weights: createRecommendationWeights(),
    type: '',
    name: '',
    description: '',
    metadata: {
      includedContentTypePrefixes: [],
      excludedContentTypePrefixes: [...DEFAULT_RECOMMENDATION_CONTENT_TYPE_EXCLUSIONS],
      includedAttributeTypes: [],
      excludedAttributeTypes: [],
    },
    collectionsEnabled: true,
    collections: {
      includedTypes: [],
      excludedTypes: [],
      includedAttributeTypes: [],
      excludedAttributeTypes: [],
    },
  }
}

export function recommendationContextToForm(
  context: RecommendationContextFormSource,
): RecommendationContextFormValues {
  const form = createRecommendationContextForm()
  form.type = context.type
  form.name = context.name
  form.description = context.description
  if (context.weights) form.weights = copyRecommendationWeights(context.weights)
  form.metadata = copyMetadataFilter(context.contentFilter.metadata)
  form.collectionsEnabled = context.contentFilter.collections !== null
  if (context.contentFilter.collections) {
    form.collections = copyCollectionFilter(context.contentFilter.collections)
  }
  return form
}

export function validateRecommendationContextForm(form: RecommendationContextFormValues): string | null {
  if (!form.name.trim()) return 'Name is required.'

  const type = normalizeContextType(form.type)
  if (!type) return 'Type is required.'
  if (!/^[a-z0-9][a-z0-9_-]*$/.test(type)) {
    return 'Type must contain only letters, numbers, hyphens, or underscores.'
  }
  return validateRecommendationWeights(form.weights)
}

export function buildRecommendationContextInput(
  form: RecommendationContextFormValues,
): RecommendationContextInput {
  return {
    type: normalizeContextType(form.type),
    name: form.name.trim(),
    weights: copyRecommendationWeights(form.weights),
    description: form.description.trim() || undefined,
    contentFilter: {
      metadata: {
        includedContentTypePrefixes: normalizeValues(form.metadata.includedContentTypePrefixes),
        excludedContentTypePrefixes: normalizeValues(form.metadata.excludedContentTypePrefixes),
        includedAttributeTypes: normalizeValues(form.metadata.includedAttributeTypes),
        excludedAttributeTypes: normalizeValues(form.metadata.excludedAttributeTypes),
      },
      collections: form.collectionsEnabled
        ? {
            includedTypes: normalizeValues(form.collections.includedTypes),
            excludedTypes: normalizeValues(form.collections.excludedTypes),
            includedAttributeTypes: normalizeValues(form.collections.includedAttributeTypes),
            excludedAttributeTypes: normalizeValues(form.collections.excludedAttributeTypes),
          }
        : null,
    },
  }
}

function normalizeContextType(type: string): string {
  return type.trim().toLowerCase()
}

function normalizeValues(values: string[]): string[] {
  return [...new Set(values.map(value => value.trim()).filter(Boolean))]
}

function copyMetadataFilter(filter: RecommendationContextFilterValues): RecommendationContextFilterValues {
  return {
    includedContentTypePrefixes: [...filter.includedContentTypePrefixes],
    excludedContentTypePrefixes: [...filter.excludedContentTypePrefixes],
    includedAttributeTypes: [...filter.includedAttributeTypes],
    excludedAttributeTypes: [...filter.excludedAttributeTypes],
  }
}

function copyCollectionFilter(filter: RecommendationCollectionFilterValues): RecommendationCollectionFilterValues {
  return {
    includedTypes: [...filter.includedTypes],
    excludedTypes: [...filter.excludedTypes],
    includedAttributeTypes: [...filter.includedAttributeTypes],
    excludedAttributeTypes: [...filter.excludedAttributeTypes],
  }
}
