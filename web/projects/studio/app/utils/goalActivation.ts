export interface ActivationEventFilter {
  eventType: string | null
  elementType?: string | null
  elementId?: string | null
  elementIdContains?: string | null
  pagePath?: string | null
  pagePathPrefixes?: string[]
  itemExtraKey?: string | null
  itemExtraValue?: string | null
  applicationIds?: string[]
  recommendationCard?: boolean
}
export interface GoalActivation {
  defaultFilter: ActivationEventFilter | null
  variationRules: { variationKey: string; mode: 'EVENT' | 'INHERIT' | 'NOT_APPLICABLE'; filter: ActivationEventFilter | null }[]
  applicationIds: string[]
  sameSession: boolean
  maxDelaySeconds: number | null
}
export interface GoalOutcomeScope {
  applicationIds: string[]
  sameSession: boolean
  maxDelaySeconds: number | null
  recommendationCard: boolean
  attributeRecommendationDestination: boolean
}
export interface MetricInterval { lower: number; upper: number }
export interface MetricDistribution {
  count: number; mean: number; standardDeviation: number
  p50: number; p75: number; p90: number; p95: number; p99: number; maximum: number
}
export interface GoalPopulation {
  subjects: number; conversions: number; observationCount: number
  estimate: number | null; variance: number | null; interval: MetricInterval | null
  distribution: MetricDistribution | null
}
export interface GoalPopulationAnalysis {
  cutoff: string; activationConfigured: boolean; notApplicable: boolean
  overall: GoalPopulation; activated: GoalPopulation | null; notActivated: GoalPopulation | null
  reach: number | null; reachInterval: MetricInterval | null
  placement: { expectedOnly: number; unexpectedOnly: number; both: number; neither: number } | null
  exposureEvents: number | null; exposureIntensity: MetricDistribution | null
  attribution: {
    engagedSubjects: number; interactions: number; attributedSubjects: number; attributedInteractions: number
    interval: MetricInterval | null; unresolvedTargets: number; unsupportedTypes: number
    missingSlugs: number; missingSessions: number; unmatchedDestinations: number
  } | null
  diagnostics: { excludedPrincipals: number; excludedInstallations: number; duplicateDeliveries: number; outsideApplication: number; missingApplication: number }
}
export interface PlannedComparison {
  goalId: string; leftVariationKey: string; rightVariationKey: string
  leftSubjects: number; rightSubjects: number; difference: number | null; interval: MetricInterval | null
  evidence: number | null; evidenceLabel: string; threshold: number; conclusion: string; reason: string
}
export const newActivationFilter = (): ActivationEventFilter => ({ eventType: 'Impression' })
export const newGoalActivation = (): GoalActivation => ({ defaultFilter: null, variationRules: [], applicationIds: [], sameSession: false, maxDelaySeconds: null })
export const newOutcomeScope = (): GoalOutcomeScope => ({ applicationIds: [], sameSession: false, maxDelaySeconds: null, recommendationCard: false, attributeRecommendationDestination: false })
export const splitIdentifiers = (value: string): string[] => [...new Set(value.split(/[\n,]/).map(v => v.trim()).filter(Boolean))]
