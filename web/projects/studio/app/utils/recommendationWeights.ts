import type { RecommendationWeights } from '~/types/graphql'

export type RecommendationWeightValues = Omit<RecommendationWeights, '__typename' | 'similarity' | 'typePreferences'> & {
  similarity: Omit<RecommendationWeights['similarity'], '__typename'>
  typePreferences: { type: string, weight: number }[]
}

export const similarityWeightFields = [
  { key: 'semantic', label: 'Semantic similarity' },
  { key: 'categories', label: 'Categories' },
  { key: 'labels', label: 'Labels' },
  { key: 'language', label: 'Language' },
  { key: 'mime', label: 'MIME type' },
  { key: 'type', label: 'Match source editorial type' },
  { key: 'collections', label: 'Shared collections' },
] as const

export const influenceWeightFields = [
  { key: 'content', label: 'Content relatedness' },
  { key: 'coEngagement', label: 'Co-engagement' },
  { key: 'cohortCoEngagement', label: 'Cohort co-engagement' },
  { key: 'learnedNeighbor', label: 'Learned neighbors' },
  { key: 'personalization', label: 'Personalization' },
  { key: 'rating', label: 'Rating affinity' },
] as const

export function describeTypePreference(preference: number, defaultPreference: number): string {
  if ([preference, defaultPreference].some(value => !Number.isFinite(value) || value < 0 || value > 1)) {
    return 'Enter preferences between 0 and 1 to compare their effect.'
  }
  // Both scorers multiply content relatedness by (1 + preference) / 2.
  const change = ((1 + preference) / (1 + defaultPreference) - 1) * 100
  if (Math.abs(change) < 0.05) return 'Same content score as unlisted types at equal relatedness.'
  const percentage = new Intl.NumberFormat('en', { maximumFractionDigits: 1 }).format(Math.abs(change))
  return `${percentage}% ${change > 0 ? 'higher' : 'lower'} content score than unlisted types at equal relatedness.`
}

export function createRecommendationWeights(): RecommendationWeightValues {
  return {
    similarity: {
      semantic: 0.2,
      categories: 0.2,
      labels: 0.2,
      language: 0.2,
      mime: 0.2,
      type: 0.2,
      collections: 0.2,
    },
    typePreferences: [],
    defaultTypePreference: 0.5,
    content: 1,
    coEngagement: 1,
    cohortCoEngagement: 1,
    learnedNeighbor: 1,
    personalization: 1,
    rating: 1,
  }
}

export function copyRecommendationWeights(weights: RecommendationWeightValues): RecommendationWeightValues {
  return {
    similarity: {
      semantic: weights.similarity.semantic,
      categories: weights.similarity.categories,
      labels: weights.similarity.labels,
      language: weights.similarity.language,
      mime: weights.similarity.mime,
      type: weights.similarity.type,
      collections: weights.similarity.collections,
    },
    typePreferences: weights.typePreferences.map(({ type, weight }) => ({ type: type.trim().toLowerCase(), weight })),
    defaultTypePreference: weights.defaultTypePreference,
    content: weights.content,
    coEngagement: weights.coEngagement,
    cohortCoEngagement: weights.cohortCoEngagement,
    learnedNeighbor: weights.learnedNeighbor,
    personalization: weights.personalization,
    rating: weights.rating,
  }
}

export function validateRecommendationWeights(weights: RecommendationWeightValues): string | null {
  const values = [
    ...similarityWeightFields.map(({ key }) => weights.similarity[key]),
    ...influenceWeightFields.map(({ key }) => weights[key]),
    weights.defaultTypePreference,
    ...weights.typePreferences.map(preference => preference.weight),
  ]
  if (values.some(value => !Number.isFinite(value) || value < 0 || value > 1)) {
    return 'Weights must be numbers between 0 and 1.'
  }
  if (similarityWeightFields.every(({ key }) => weights.similarity[key] === 0)) {
    return 'At least one similarity weight must be greater than zero.'
  }
  const types = weights.typePreferences.map(({ type }) => type.trim().toLowerCase())
  if (types.some(type => !type)) return 'Each type preference needs an editorial type.'
  if (new Set(types).size !== types.length) return 'Editorial type preferences must have distinct types.'
  return null
}
