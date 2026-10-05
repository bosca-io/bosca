<script setup lang="ts">
import gql from 'graphql-tag'
import type { SelectOption } from '@bosca/ui'
import { useExperimentGoals, type ConversionGoalSnapshot } from '~/composables/useExperimentGoals'
import { useExperimentRolloutPolicy } from '~/composables/useExperimentRolloutPolicy'
import { hasCoverageImbalance } from '~/utils/experimentResultMath'

const route = useRoute()
const router = useRouter()
const { accent } = useCurrentSubsystem()
const { useAsyncQuery, mutation: gqlMutation } = useGraphQL()
const toast = useToast()

const experimentId = computed(() => route.params.id as string)

// ── Constants ────────────────────────────────────────────────────────

const STATUS_COLORS: Record<string, string> = {
  RUNNING: '#34d99a',
  DRAFT: '#6c7388',
  PAUSED: '#f59e42',
  COMPLETED: '#5ec5ff',
  ARCHIVED: '#3a4256',
}

const VERDICT_COLORS: Record<string, string> = {
  SHIP: '#34d99a',
  DO_NOT_SHIP: '#e5484d',
  HALT: '#e5484d',
  KEEP_RUNNING: '#f59e42',
  INCONCLUSIVE: '#6c7388',
  SRM_FAILED: '#e5484d',
}

const ROLE_COLORS: Record<string, string> = {
  PRIMARY: '#34d99a',
  SECONDARY: '#5ec5ff',
  GUARDRAIL: '#f59e42',
}

const ROLLOUT_ACTION_COLORS: Record<string, string> = {
  ADVANCED: '#34d99a',
  COMPLETED: '#5ec5ff',
  HALTED: '#e5484d',
  HELD: '#6c7388',
}

const ANALYSIS_METHOD_OPTIONS: SelectOption[] = [
  { value: 'FREQUENTIST', label: 'Frequentist (chi-squared / Welch\'s t, Bonferroni-corrected)' },
  { value: 'BAYESIAN', label: 'Bayesian (P(treatment > control) posteriors, no Bonferroni)' },
]

const ANY_ACTIVATION_EVENT_TYPE = '__any__'
const ACTIVATION_EVENT_TYPE_OPTIONS: SelectOption[] = [
  { value: ANY_ACTIVATION_EVENT_TYPE, label: '(any event type)' },
  { value: 'Impression', label: 'Impression' },
  { value: 'Interaction', label: 'Interaction' },
  { value: 'Completion', label: 'Completion' },
  { value: 'Session', label: 'Session' },
]

// ── GraphQL ──────────────────────────────────────────────────────────

const experimentGql = gql`
  query GetExperiment($id: UUID!) {
    experiments {
      experiment(id: $id) {
        id name description hypothesis status
        startDate endDate targetSampleSize created modified
        featureFlagId targetingRuleId controlVariationKey excludedPrincipalIds
        activationFilter {
          eventType elementType elementId pagePath pagePathPrefixes itemExtraKey itemExtraValue
        }
        featureFlag { id key name type variations targetingRules }
        exclusionLayer { id name }
        analysisMethod
        bayesianPrior { betaPriorAlpha betaPriorBeta normalPriorMean normalPriorVariance }
        rolloutPolicy {
          mode treatmentVariationKey
          steps { weightPercent afterDuration }
          incrementPercent minConfidence
          guardrailThreshold guardrailMinRegressionPercent haltOnGuardrail
        }
        rolloutPolicyEvents(offset: 0, limit: 50) {
          id action reason oldWeights newWeights created
        }
        conversionGoals {
          id name eventType elementType elementId metricType pagePath pagePathPrefixes itemExtraKey itemExtraValue role
          cupedCovariate { eventType elementType elementId pagePath lookbackWindow }
        }
        results {
          id variationKey goalId
          goal { id name metricType role }
          assignments impressions observationCount conversions conversionRate
          confidenceLevel liftOverControl
          mean variance
          probabilityBeatsControl expectedLoss
          adjustedMean adjustedVariance
          updatedAt
        }
        analysisReports {
          id summary recommendation confidence details
          aiInsights {
            hypothesisAssessment crossGoalPatterns
            followUpExperiments srmRootCauseHints
          }
          created
        }
      }
    }
  }
`

const exclusionLayersGql = gql`
  query ListExclusionLayersForExperiment {
    exclusionLayers { all { id name } }
  }
`

const setStatusGql = gql`
  mutation SetExperimentStatus($id: UUID!, $status: ExperimentStatus!) {
    experiments { setStatus(id: $id, status: $status) { id status } }
  }
`

const editGql = gql`
  mutation EditExperiment($id: UUID!, $experiment: ExperimentInput!) {
    experiments { edit(id: $id, experiment: $experiment) { id } }
  }
`

const aggregateGql = gql`
  mutation AggregateResults($id: UUID!) {
    experiments { aggregateResults(experimentId: $id) }
  }
`

const analyzeGql = gql`
  mutation AnalyzeExperiment($id: UUID!) {
    experiments { analyze(experimentId: $id) }
  }
`

// ── Types ────────────────────────────────────────────────────────────

interface FeatureFlagVariation {
  key: string
  name: string
  description?: string
  value: unknown
}

interface FeatureFlagRollout {
  variationKey: string
  weight: number
}

interface FeatureFlagTargetingRule {
  id: string
  name?: string
  description?: string
  conditions?: unknown[]
  rollout?: { variationWeights?: FeatureFlagRollout[] }
}

interface FeatureFlagRef {
  id: string
  key: string
  name: string
  type: string | null
  variations: FeatureFlagVariation[]
  targetingRules: FeatureFlagTargetingRule[]
}

interface BayesianPrior {
  betaPriorAlpha: number | null
  betaPriorBeta: number | null
  normalPriorMean: number | null
  normalPriorVariance: number | null
}

interface RolloutPolicySnapshot {
  mode: string
  treatmentVariationKey: string | null
  steps: Array<{ weightPercent: number; afterDuration: string | null }>
  incrementPercent: number | null
  minConfidence: number | null
  guardrailThreshold: number | null
  guardrailMinRegressionPercent: number | null
  haltOnGuardrail: boolean | null
}

interface RolloutPolicyEvent {
  id: string
  action: string
  reason: string | null
  oldWeights: Record<string, unknown> | null
  newWeights: Record<string, unknown> | null
  created: string
}

interface ExperimentResult {
  id: string
  variationKey: string
  goalId: string
  goal: { id: string; name: string; metricType: string; role: string } | null
  assignments: number
  impressions: number
  observationCount: number
  conversions: number
  conversionRate: number
  confidenceLevel: number | null
  liftOverControl: number | null
  mean: number | null
  variance: number | null
  probabilityBeatsControl: number | null
  expectedLoss: number | null
  adjustedMean: number | null
  adjustedVariance: number | null
  updatedAt: string | null
}

interface ExperimentActivationFilter {
  eventType: string | null
  elementType: string | null
  elementId: string | null
  pagePath: string | null
  pagePathPrefixes: string[]
  itemExtraKey: string | null
  itemExtraValue: string | null
}

interface AIInsights {
  hypothesisAssessment: string | null
  crossGoalPatterns: string | null
  followUpExperiments: string[] | string | null
  srmRootCauseHints: string | null
}

interface AnalysisReport {
  id: string
  summary: string | null
  recommendation: string | null
  confidence: number | null
  details: Record<string, unknown> | null
  aiInsights: AIInsights | null
  created: string
}

interface Experiment {
  id: string
  name: string
  description: string | null
  hypothesis: string | null
  status: string
  startDate: string | null
  endDate: string | null
  targetSampleSize: number | null
  created: string
  modified: string
  featureFlagId: string | null
  targetingRuleId: string | null
  controlVariationKey: string
  excludedPrincipalIds: string[]
  activationFilter: ExperimentActivationFilter | null
  featureFlag: FeatureFlagRef | null
  exclusionLayer: { id: string; name: string } | null
  analysisMethod: string | null
  bayesianPrior: BayesianPrior | null
  rolloutPolicy: RolloutPolicySnapshot | null
  rolloutPolicyEvents: RolloutPolicyEvent[]
  conversionGoals: ConversionGoalSnapshot[]
  results: ExperimentResult[]
  analysisReports: AnalysisReport[]
}

// ── Data ─────────────────────────────────────────────────────────────

const { data, status, refresh } = useAsyncQuery<{
  experiments: { experiment: Experiment | null }
}>('experiment-detail', experimentGql, { id: experimentId }, { server: false })

const { data: layersData } = useAsyncQuery<{
  exclusionLayers: { all: Array<{ id: string; name: string }> }
}>('experiment-layers', exclusionLayersGql, {}, { server: false })

const experiment = computed(() => data.value?.experiments?.experiment ?? null)
const availableLayers = computed(() => layersData.value?.exclusionLayers?.all ?? [])
const isLoading = computed(() => status.value === 'pending')

// ── Inline-editable scalar fields ────────────────────────────────────

const editName = ref('')
const editHypothesis = ref('')
const editDescription = ref('')

watch(experiment, (exp) => {
  if (!exp) return
  editName.value = exp.name ?? ''
  editHypothesis.value = exp.hypothesis ?? ''
  editDescription.value = exp.description ?? ''
}, { immediate: true })

const detailsDirty = computed(() => {
  const exp = experiment.value
  if (!exp) return false
  return editName.value !== (exp.name ?? '')
    || editHypothesis.value !== (exp.hypothesis ?? '')
    || editDescription.value !== (exp.description ?? '')
})

// ── Activation filter ────────────────────────────────────────────────

const activationEnabled = ref(false)
const activationEventType = ref('Impression')
const activationElementType = ref('page')
const activationElementId = ref('')
const activationPagePath = ref('')
const activationPagePathPrefixes = ref('')
const activationItemExtraKey = ref('')
const activationItemExtraValueEnabled = ref(false)
const activationItemExtraValue = ref('')
const savingActivation = ref(false)

watch(experiment, (exp) => {
  if (!exp) return
  const filter = exp.activationFilter
  activationEnabled.value = filter != null
  activationEventType.value = filter == null ? 'Impression' : (filter.eventType ?? ANY_ACTIVATION_EVENT_TYPE)
  activationElementType.value = filter == null ? 'page' : (filter.elementType ?? '')
  activationElementId.value = filter?.elementId ?? ''
  activationPagePath.value = filter?.pagePath ?? ''
  activationPagePathPrefixes.value = filter?.pagePathPrefixes?.join('\n') ?? ''
  activationItemExtraKey.value = filter?.itemExtraKey ?? ''
  activationItemExtraValueEnabled.value = filter?.itemExtraValue != null
  activationItemExtraValue.value = filter?.itemExtraValue ?? ''
}, { immediate: true })

function activationFilterPayload(): Record<string, unknown> | null {
  if (!activationEnabled.value) return null
  return {
    eventType: activationEventType.value === ANY_ACTIVATION_EVENT_TYPE ? null : activationEventType.value,
    elementType: activationElementType.value.trim() || null,
    elementId: activationElementId.value.trim() || null,
    pagePath: activationPagePath.value.trim() || null,
    pagePathPrefixes: [...new Set(
      activationPagePathPrefixes.value.split(/[\n,]/).map((value) => value.trim()).filter(Boolean),
    )],
    itemExtraKey: activationItemExtraKey.value.trim() || null,
    itemExtraValue: activationItemExtraValueEnabled.value ? activationItemExtraValue.value : null,
  }
}

// ── Analysis method & Bayesian priors ────────────────────────────────

const analysisMethodValue = ref<string>('FREQUENTIST')
const bayesianBetaAlpha = ref<number | null>(null)
const bayesianBetaBeta = ref<number | null>(null)
const bayesianNormalMean = ref<number | null>(null)
const bayesianNormalVariance = ref<number | null>(null)
const savingAnalysisMethod = ref(false)

watch(experiment, (exp) => {
  if (!exp) return
  analysisMethodValue.value = exp.analysisMethod ?? 'FREQUENTIST'
  const prior = exp.bayesianPrior
  bayesianBetaAlpha.value = prior?.betaPriorAlpha ?? null
  bayesianBetaBeta.value = prior?.betaPriorBeta ?? null
  bayesianNormalMean.value = prior?.normalPriorMean ?? null
  bayesianNormalVariance.value = prior?.normalPriorVariance ?? null
}, { immediate: true })

// Build the bayesianPrior payload, returning null when the operator hasn't
// customized anything. This keeps the default-defaults path explicit on
// the wire (server reads null and applies Beta(1,1) + flat Normal).
function bayesianPriorPayload(): Record<string, unknown> | null {
  const customized =
    bayesianBetaAlpha.value != null
    || bayesianBetaBeta.value != null
    || bayesianNormalMean.value != null
    || bayesianNormalVariance.value != null
  if (!customized) return null
  return {
    betaPriorAlpha: bayesianBetaAlpha.value ?? 1.0,
    betaPriorBeta: bayesianBetaBeta.value ?? 1.0,
    normalPriorMean: bayesianNormalMean.value,
    normalPriorVariance: bayesianNormalVariance.value,
  }
}

// ── Attached rule + persisted control ────────────────────────────────

const flagVariations = computed<FeatureFlagVariation[]>(() => {
  const v = experiment.value?.featureFlag?.variations
  return Array.isArray(v) ? v : []
})

const attachedRule = computed<FeatureFlagTargetingRule | null>(() => {
  const rules = experiment.value?.featureFlag?.targetingRules
  if (!Array.isArray(rules)) return null
  const ruleId = experiment.value?.targetingRuleId
  if (!ruleId) return null
  return rules.find((r) => r.id === ruleId) ?? null
})

const involvedVariationKeys = computed<string[]>(() => {
  const rule = attachedRule.value
  if (!rule) return flagVariations.value.map((v) => v.key)
  return (rule.rollout?.variationWeights ?? []).map((vw) => vw.variationKey)
})

const involvedVariationWeights = computed(() => attachedRule.value?.rollout?.variationWeights ?? [])

const totalRolloutWeight = computed(() =>
  involvedVariationWeights.value.reduce((sum, vw) => sum + (vw.weight ?? 0), 0),
)

function rolloutShare(weight: number): string {
  if (!totalRolloutWeight.value) return '0%'
  return `${((weight / totalRolloutWeight.value) * 100).toFixed(1)}%`
}

const controlVariationKey = computed<string | null>(() => experiment.value?.controlVariationKey ?? null)

const controlVariationOptions = computed<SelectOption[]>(() =>
  involvedVariationKeys.value.map((key) => {
    const variation = flagVariations.value.find((candidate) => candidate.key === key)
    const weight = involvedVariationWeights.value.find((candidate) => candidate.variationKey === key)?.weight
    const share = weight != null ? ` · ${rolloutShare(weight)}` : ''
    return {
      value: key,
      label: `${variation?.name ?? key} (${key}) · ${variationValueDisplay(key)}${share}`,
    }
  }),
)

function variationName(key: string): string {
  return flagVariations.value.find((v) => v.key === key)?.name ?? key
}

function variationValueDisplay(key: string): string {
  const v = flagVariations.value.find((variation) => variation.key === key)?.value
  if (v === true) return 'Enabled'
  if (v === false) return 'Disabled'
  if (typeof v === 'string') return `"${v}"`
  if (v == null) return 'null'
  return JSON.stringify(v)
}

// ── Edit-experiment helpers (full input, no field-stripping) ─────────

// Builds a complete `ExperimentInput` from the current state with optional
// field overrides. Every save flows through this so unrelated fields are
// preserved verbatim — sending a partial input would silently null out the
// rollout policy, analysis method, or exclusion layer.
function buildExperimentInput(overrides: Record<string, unknown> = {}): Record<string, unknown> | null {
  const exp = experiment.value
  if (!exp) return null
  return {
    featureFlagId: exp.featureFlagId,
    name: editName.value.trim() || exp.name,
    description: editDescription.value.trim() || null,
    hypothesis: editHypothesis.value.trim() || null,
    targetingRuleId: exp.targetingRuleId ?? null,
    controlVariationKey: exp.controlVariationKey,
    excludedPrincipalIds: exp.excludedPrincipalIds,
    activationFilter: exp.activationFilter,
    exclusionLayerId: exp.exclusionLayer?.id ?? null,
    startDate: exp.startDate ?? null,
    endDate: exp.endDate ?? null,
    targetSampleSize: exp.targetSampleSize ?? null,
    rolloutPolicy: rolloutPolicy.rolloutPolicyPayload(),
    analysisMethod: analysisMethodValue.value,
    bayesianPrior: bayesianPriorPayload(),
    ...overrides,
  }
}

async function saveActivationFilter() {
  savingActivation.value = true
  try {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput({ activationFilter: activationFilterPayload() }),
    })
    toast.success(activationEnabled.value ? 'Activation filter saved' : 'Activation filter cleared')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save activation filter')
  } finally {
    savingActivation.value = false
  }
}

// ── Save & status mutations ──────────────────────────────────────────

const savingDetails = ref(false)
async function saveDetails() {
  if (!experiment.value) return
  if (!editName.value.trim()) {
    toast.error('Name is required')
    return
  }
  savingDetails.value = true
  try {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput(),
    })
    toast.success('Experiment saved')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save experiment')
  } finally {
    savingDetails.value = false
  }
}

const savingLayer = ref(false)
async function updateExclusionLayer(newId: string | null) {
  const exp = experiment.value
  if (!exp) return
  const currentId = exp.exclusionLayer?.id ?? null
  if (newId === currentId) return
  savingLayer.value = true
  try {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput({ exclusionLayerId: newId }),
    })
    toast.success(newId ? 'Exclusion layer attached' : 'Exclusion layer detached')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update exclusion layer')
  } finally {
    savingLayer.value = false
  }
}

const savingControl = ref(false)
async function updateControlVariation(controlKey: string | string[] | null | undefined) {
  if (typeof controlKey !== 'string') return
  const exp = experiment.value
  if (!exp || exp.status !== 'DRAFT' || controlKey === exp.controlVariationKey) return
  savingControl.value = true
  try {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput({ controlVariationKey: controlKey }),
    })
    toast.success('Control variation updated')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update control variation')
  } finally {
    savingControl.value = false
  }
}

async function saveAnalysisMethod() {
  if (!experiment.value) return
  savingAnalysisMethod.value = true
  try {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput(),
    })
    toast.success('Analysis method saved')
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to save analysis method')
  } finally {
    savingAnalysisMethod.value = false
  }
}

async function setStatus(newStatus: string) {
  try {
    await gqlMutation(setStatusGql, { id: experimentId.value, status: newStatus })
    toast.success(`Status set to ${newStatus.toLowerCase()}`)
    await refresh()
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to update status')
  }
}

// ── Rollout-policy editor ────────────────────────────────────────────

const rolloutPolicy = useExperimentRolloutPolicy({
  involvedVariationKeys,
  controlVariationKey,
  async savePolicy(payload) {
    await gqlMutation(editGql, {
      id: experimentId.value,
      experiment: buildExperimentInput({ rolloutPolicy: payload }),
    })
    await refresh()
  },
})

watch(experiment, (exp) => {
  if (exp) rolloutPolicy.hydrate(exp.rolloutPolicy)
}, { immediate: true })

const treatmentVariationOptions = computed<SelectOption[]>(() =>
  involvedVariationKeys.value.filter((k) => k !== controlVariationKey.value).map((k) => ({
    value: k,
    label: `${variationName(k)} (${k})`,
  })),
)

// ── Aggregate / Analyze with polling ─────────────────────────────────

let isMounted = true
const abortController = typeof AbortController !== 'undefined' ? new AbortController() : null
onUnmounted(() => {
  isMounted = false
  abortController?.abort()
})

function latestResultsUpdatedAt(): number {
  let max = 0
  for (const r of experiment.value?.results ?? []) {
    const t = r.updatedAt ? new Date(r.updatedAt).getTime() : 0
    if (t > max) max = t
  }
  return max
}

function analysisReportCount(): number {
  return (experiment.value?.analysisReports ?? []).length
}

async function pollUntil(
  predicate: () => boolean,
  options: { intervalMs?: number; timeoutMs?: number } = {},
): Promise<boolean> {
  const intervalMs = options.intervalMs ?? 2000
  const timeoutMs = options.timeoutMs ?? 60000
  const start = Date.now()
  while (Date.now() - start < timeoutMs && isMounted) {
    await new Promise<void>((resolve) => {
      const timer = setTimeout(resolve, intervalMs)
      abortController?.signal.addEventListener('abort', () => {
        clearTimeout(timer)
        resolve()
      }, { once: true })
    })
    if (!isMounted) return false
    await refresh()
    if (predicate()) return true
  }
  return false
}

const aggregating = ref(false)
async function onAggregate() {
  if (aggregating.value) return
  aggregating.value = true
  const before = latestResultsUpdatedAt()
  try {
    await gqlMutation(aggregateGql, { id: experimentId.value })
    toast.info('Aggregation job submitted')
    const arrived = await pollUntil(() => latestResultsUpdatedAt() > before)
    if (arrived) {
      toast.success('Aggregation complete')
    } else {
      toast.warn('Aggregation timed out — refresh manually to check')
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to aggregate results')
  } finally {
    aggregating.value = false
  }
}

const analyzing = ref(false)
async function onAnalyze() {
  if (analyzing.value) return
  analyzing.value = true
  const before = analysisReportCount()
  try {
    await gqlMutation(analyzeGql, { id: experimentId.value })
    toast.info('Analysis job submitted')
    const arrived = await pollUntil(() => analysisReportCount() > before)
    if (arrived) {
      toast.success('Analysis complete')
    } else {
      toast.warn('Analysis timed out — refresh manually to check')
    }
  } catch (e: unknown) {
    toast.error(e instanceof Error ? e.message : 'Failed to analyze experiment')
  } finally {
    analyzing.value = false
  }
}

// ── Conversion goals composable ──────────────────────────────────────

const goals = useExperimentGoals({ experimentId, refresh })

// ── Verdict banner (sorted reports, broader vocabulary) ──────────────

const sortedAnalysisReports = computed(() => {
  const reports = experiment.value?.analysisReports ?? []
  return [...reports].sort((a, b) =>
    String(b.created ?? '').localeCompare(String(a.created ?? '')),
  )
})

function isCurrentAnalysisReport(report: AnalysisReport): boolean {
  if (!report.details || typeof report.details !== 'object') return false
  return (report.details as Record<string, unknown>).isCurrent === true
}

const latestReport = computed<AnalysisReport | null>(() =>
  sortedAnalysisReports.value.find(isCurrentAnalysisReport) ?? null,
)

// Prefer `details.verdict` (broader vocabulary including HALT, SRM_FAILED,
// SHIP, DO_NOT_SHIP, KEEP_RUNNING, INCONCLUSIVE) and fall back to
// `recommendation` so older reports still surface a banner.
const latestVerdict = computed<string | null>(() => {
  const report = latestReport.value
  if (!report) return null
  const detailsVerdict = report.details && typeof report.details === 'object'
    ? (report.details as Record<string, unknown>).verdict
    : null
  if (typeof detailsVerdict === 'string' && detailsVerdict) return detailsVerdict
  return report.recommendation ?? null
})

// ── Per-goal result pivot, with control row + lift + duplicate detect ──

interface DecoratedRow {
  variationKey: string
  isControl: boolean
  assignments: number
  impressions: number
  observationCount: number
  conversions: number
  conversionRate: number
  mean: number | null
  variance: number | null
  confidenceLevel: number | null
  probabilityBeatsControl: number | null
  expectedLoss: number | null
  adjustedMean: number | null
  adjustedVariance: number | null
  cupedApplied: boolean
  coverage: number
  lift: number | null
}

interface GoalGroup {
  goalId: string
  goalName: string
  definitionFingerprint: string
  metricType: string
  role: string
  cupedConfigured: boolean
  filterDescription: string
  totalAssignments: number
  totalImpressions: number
  totalConversions: number
  totalObservations: number
  rows: DecoratedRow[]
  chartMax: number
  unit: string | null
  coverageImbalanced: boolean
  broadImpressionGoal: boolean
}

function goalDefinitionFingerprint(goal: ConversionGoalSnapshot | undefined, metricType: string): string {
  const cuped = goal?.cupedCovariate
  return JSON.stringify([
    metricType,
    goal?.eventType ?? null,
    goal?.elementType ?? null,
    goal?.elementId ?? null,
    goal?.pagePath ?? null,
    goal?.pagePathPrefixes ?? [],
    goal?.itemExtraKey ?? null,
    goal?.itemExtraValue ?? null,
    cuped == null
      ? null
      : [
          cuped.eventType ?? null,
          cuped.elementType ?? null,
          cuped.elementId ?? null,
          cuped.pagePath ?? null,
          cuped.lookbackWindow ?? null,
        ],
  ])
}

const resultsByGoal = computed<GoalGroup[]>(() => {
  const results = experiment.value?.results ?? []
  const allGoals = experiment.value?.conversionGoals ?? []
  if (!results.length) return []

  const goalOrder = allGoals.map((g) => g.id)
  const byGoalId = new Map<string, ExperimentResult[]>()
  for (const r of results) {
    const arr = byGoalId.get(r.goalId) ?? []
    arr.push(r)
    byGoalId.set(r.goalId, arr)
  }

  return goalOrder
    .filter((id) => byGoalId.has(id))
    .map<GoalGroup>((goalId) => {
      const goal = allGoals.find((g) => g.id === goalId)
      const rows = byGoalId.get(goalId) ?? []
      const metricType = goal?.metricType ?? 'UNIQUE_CONVERSION'

      const sorted = [...rows].sort((a, b) =>
        String(a.variationKey).localeCompare(String(b.variationKey)),
      )
      const filterParts: string[] = []
      filterParts.push(`type=${goal?.eventType ?? 'any'}`)
      if (goal?.elementType) filterParts.push(`element.type=${goal.elementType}`)
      if (goal?.elementId) filterParts.push(`element.id=${goal.elementId}`)
      const pageFilters: string[] = []
      if (goal?.pagePath) pageFilters.push(`page.path=${goal.pagePath}`)
      if (goal?.pagePathPrefixes?.length) {
        pageFilters.push(`page.path starts with one of [${goal.pagePathPrefixes.join(', ')}]`)
      }
      if (pageFilters.length) {
        const pageFilter = pageFilters.join(' OR ')
        filterParts.push(pageFilters.length > 1 ? `(${pageFilter})` : pageFilter)
      }
      if (goal?.itemExtraKey) {
        filterParts.push(`item.extra.${goal.itemExtraKey}${goal.itemExtraValue != null ? `=${JSON.stringify(goal.itemExtraValue)}` : ' present'}`)
      }
      if (metricType === 'SESSION_DURATION') filterParts.push('session duration including playback')
      const filterDescription = filterParts.join(' ∧ ')

      const totalAssignments = rows.reduce((sum, r) => sum + (r.assignments ?? r.impressions ?? 0), 0)
      const totalImpressions = rows.reduce((sum, r) => sum + (r.impressions ?? 0), 0)
      const totalConversions = rows.reduce((sum, r) => sum + (r.conversions ?? 0), 0)
      const totalObservations = rows.reduce((sum, r) => sum + (r.observationCount ?? r.impressions ?? 0), 0)

      const decoratedRows: DecoratedRow[] = sorted.map((r) => {
        const isControl = r.variationKey === controlVariationKey.value
        return {
          variationKey: r.variationKey,
          isControl,
          assignments: r.assignments ?? r.impressions ?? 0,
          impressions: r.impressions ?? 0,
          observationCount: r.observationCount ?? r.impressions ?? 0,
          conversions: r.conversions ?? 0,
          conversionRate: r.conversionRate ?? 0,
          mean: r.mean,
          variance: r.variance,
          confidenceLevel: r.confidenceLevel,
          probabilityBeatsControl: r.probabilityBeatsControl,
          expectedLoss: r.expectedLoss,
          adjustedMean: r.adjustedMean,
          adjustedVariance: r.adjustedVariance,
          cupedApplied: r.adjustedMean != null,
          coverage: (r.impressions ?? 0) > 0 ? (r.observationCount ?? r.impressions ?? 0) / r.impressions : 0,
          lift: isControl ? null : r.liftOverControl,
        }
      })

      // Chart max for the inline bar comparison — use the metric value
      // operators care about for this metric type, including zero values.
      const metricValues = decoratedRows.map((row) =>
        metricType === 'EVENT_COUNT' || metricType === 'SESSION_DURATION'
          ? (row.mean ?? 0)
          : (row.conversionRate ?? 0) * 100,
      )
      const chartMax = Math.max(0.0001, ...metricValues)

      return {
        goalId,
        goalName: goal?.name ?? 'Unnamed goal',
        definitionFingerprint: goalDefinitionFingerprint(goal, metricType),
        metricType,
        role: goal?.role ?? 'PRIMARY',
        cupedConfigured: goal?.cupedCovariate != null,
        filterDescription,
        totalAssignments,
        totalImpressions,
        totalConversions,
        totalObservations,
        rows: decoratedRows,
        chartMax,
        unit: metricType === 'SESSION_DURATION' ? 'seconds' : null,
        coverageImbalanced: metricType === 'SESSION_DURATION'
          && hasCoverageImbalance(decoratedRows),
        broadImpressionGoal: metricType !== 'SESSION_DURATION'
          && goal?.eventType?.toLowerCase() === 'impression'
          && !goal?.elementType && !goal?.elementId && !goal?.itemExtraKey,
      }
    })
})

const duplicateGoalIds = computed<Set<string>>(() => {
  const byFingerprint = new Map<string, string[]>()
  for (const g of resultsByGoal.value) {
    const resultFingerprint = g.rows
      .map((r) => `${r.variationKey}:${r.impressions}:${r.conversions}:${r.mean ?? ''}`)
      .join('|')
    const fp = JSON.stringify([g.definitionFingerprint, resultFingerprint])
    const ids = byFingerprint.get(fp) ?? []
    ids.push(g.goalId)
    byFingerprint.set(fp, ids)
  }
  const dupes = new Set<string>()
  for (const ids of byFingerprint.values()) {
    if (ids.length > 1) ids.forEach((id) => dupes.add(id))
  }
  return dupes
})

const lastAggregatedAt = computed(() => {
  const t = latestResultsUpdatedAt()
  return t ? new Date(t).toLocaleString() : ''
})

// ── Exclusion-layer dropdown options ─────────────────────────────────

const NO_LAYER_SENTINEL = '__none__'
const layerOptions = computed<SelectOption[]>(() => {
  const opts: SelectOption[] = [{ value: NO_LAYER_SENTINEL, label: 'No exclusion layer' }]
  for (const l of availableLayers.value) opts.push({ value: l.id, label: l.name })
  return opts
})
const selectedLayerValue = computed<string>({
  get: () => experiment.value?.exclusionLayer?.id ?? NO_LAYER_SENTINEL,
  set: (v) => updateExclusionLayer(v === NO_LAYER_SENTINEL ? null : v),
})

// ── Formatters ───────────────────────────────────────────────────────

function formatDate(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' })
}

function formatDateTime(d: string | null | undefined): string {
  if (!d) return '—'
  return new Date(d).toLocaleString(undefined, {
    month: 'short', day: 'numeric', year: 'numeric',
    hour: '2-digit', minute: '2-digit',
  })
}

function formatPct(n: number | null | undefined): string {
  if (n == null) return '—'
  return `${(n * 100).toFixed(2)}%`
}

function formatLift(n: number | null | undefined): string {
  if (n == null) return '—'
  const pct = n.toFixed(1)
  return n > 0 ? `+${pct}%` : `${pct}%`
}

function metricLabel(metricType: string): string {
  if (metricType === 'SESSION_DURATION') return 'Session Duration'
  if (metricType === 'EVENT_COUNT') return 'Event Count'
  return 'Unique Conversion'
}

function liftColor(n: number | null | undefined): string {
  if (n == null) return 'var(--fg-3)'
  if (n > 0) return '#34d99a'
  if (n < 0) return '#e5484d'
  return 'var(--fg-3)'
}

function confidenceColor(level: number | null): string {
  if (level == null) return '#6c7388'
  if (level >= 0.95) return '#34d99a'
  if (level >= 0.90) return '#f59e42'
  return '#6c7388'
}

function formatWeightSnapshot(snapshot: Record<string, unknown> | null): string {
  if (!snapshot) return '—'
  return Object.entries(snapshot).map(([k, v]) => `${k}=${v}`).join(', ')
}

// ── Status flow helpers ──────────────────────────────────────────────

const attachedRuleVariationKeys = computed(() => {
  const rule = attachedRule.value
  if (!rule) return []
  const palette = new Set(flagVariations.value.map(variation => variation.key))
  return [...new Set(
    (rule.rollout?.variationWeights ?? [])
      .map(weight => weight.variationKey)
      .filter(key => palette.has(key)),
  )]
})

const startBlockedReason = computed(() => {
  const exp = experiment.value
  if (!exp) return null
  if (exp.status !== 'DRAFT' && exp.status !== 'PAUSED') return null
  if (exp.targetingRuleId == null) {
    return 'This legacy experiment has no targeting rule and cannot start. Recreate it with an attached rule.'
  }
  if (attachedRule.value == null) {
    return 'This experiment references a targeting rule that no longer exists and cannot start. Recreate it with an attached rule.'
  }
  if (attachedRuleVariationKeys.value.length < 2) {
    return 'The attached targeting rule serves fewer than two variations. Add another variation before starting the experiment.'
  }
  if (!attachedRuleVariationKeys.value.includes(exp.controlVariationKey)) {
    return 'The experiment control is no longer served by its targeting rule. Restore that variation before starting the experiment.'
  }
  const treatmentKey = exp.rolloutPolicy?.treatmentVariationKey
  if (treatmentKey === exp.controlVariationKey) {
    return 'The rollout treatment must differ from the experiment control before starting the experiment.'
  }
  if (treatmentKey != null && !attachedRuleVariationKeys.value.includes(treatmentKey)) {
    return 'The rollout treatment is no longer served by the targeting rule. Restore that variation before starting the experiment.'
  }
  return null
})
const canStart = computed(() => {
  const status = experiment.value?.status
  return (status === 'DRAFT' || status === 'PAUSED') && startBlockedReason.value == null
})
const canPause = computed(() => experiment.value?.status === 'RUNNING')
const canComplete = computed(() => {
  const s = experiment.value?.status
  return s === 'RUNNING' || s === 'PAUSED'
})
const canArchive = computed(() => {
  const s = experiment.value?.status
  return s === 'DRAFT' || s === 'COMPLETED'
})
</script>

<template>
  <PageShell>
    <template #header>
      <PageHeader
        :accent="accent"
        :breadcrumb="buildBreadcrumb('Experiments', 'Experiments', experiment?.name ?? '…')"
        :title="experiment?.name ?? 'Loading…'"
        :subtitle="experiment?.status ?? ''"
      >
        <template #actions>
          <Button
            size="sm"
            icon="pulse"
            :disabled="aggregating"
            @click="onAggregate">{{ aggregating ? 'Aggregating…' : 'Aggregate' }}</Button>
          <Button
            size="sm"
            icon="wand"
            :disabled="analyzing"
            @click="onAnalyze">{{ analyzing ? 'Analyzing…' : 'Analyze' }}</Button>
        </template>
      </PageHeader>
    </template>

    <div v-if="isLoading && !experiment" class="loading-state">Loading…</div>

    <div v-else-if="!experiment" class="error-state">
      <p>Failed to load experiment.</p>
      <Button size="sm" icon="refresh" @click="refresh()">Retry</Button>
      <Button size="sm" icon="arrow-left" @click="router.push('/experiments/exp')">Back to experiments</Button>
    </div>

    <template v-else>
      <!-- Verdict banner. Hoisted to the top so HALT / SRM_FAILED dominate
           a SHIP — a guardrail trip should never be buried under a primary
           winner. -->
      <div
        v-if="latestVerdict"
        class="verdict-banner"
        :style="{ borderLeftColor: VERDICT_COLORS[latestVerdict] ?? '#6c7388' }"
      >
        <div class="verdict-row">
          <Badge :color="VERDICT_COLORS[latestVerdict] ?? '#6c7388'">
            {{ latestVerdict.replace(/_/g, ' ') }}
          </Badge>
          <span class="verdict-label">Latest verdict</span>
          <span v-if="latestReport?.confidence != null" class="verdict-confidence">
            · {{ formatPct(latestReport.confidence) }} confidence
          </span>
        </div>
        <p v-if="latestReport?.summary" class="verdict-summary">{{ latestReport.summary }}</p>
        <p v-if="latestReport?.recommendation && latestReport.recommendation !== latestVerdict" class="verdict-recommendation">
          {{ latestReport.recommendation.replace(/_/g, ' ') }}
        </p>
      </div>

      <div class="detail-layout">
        <div class="main-content">
          <!-- Overview / Details editor -->
          <SectionCard padded title="Overview">
            <template #right>
              <div class="status-actions">
                <Button
                  v-if="canStart"
                  size="xs"
                  icon="pulse"
                  primary
                  :accent="accent"
                  @click="setStatus('RUNNING')">{{ experiment.status === 'DRAFT' ? 'Start' : 'Resume' }}</Button>
                <Button
                  v-if="canPause"
                  size="xs"
                  icon="pause"
                  @click="setStatus('PAUSED')">Pause</Button>
                <Button
                  v-if="canComplete"
                  size="xs"
                  icon="check"
                  @click="setStatus('COMPLETED')">Complete</Button>
                <Button
                  v-if="canArchive"
                  size="xs"
                  icon="archive"
                  @click="setStatus('ARCHIVED')">Archive</Button>
              </div>
              <p v-if="startBlockedReason" class="start-blocked-message">{{ startBlockedReason }}</p>
            </template>

            <div class="overview-fields">
              <FormField label="Name">
                <TextInput v-model="editName" placeholder="Experiment name" />
              </FormField>
              <FormField label="Hypothesis" help="What you expect to happen and why">
                <Textarea v-model="editHypothesis" :rows="3" placeholder="We believe that…" />
              </FormField>
              <FormField label="Description">
                <Textarea v-model="editDescription" :rows="3" placeholder="Experiment description" />
              </FormField>
              <div v-if="experiment.featureFlag" class="field-inline">
                <span class="field-inline-label">Feature Flag</span>
                <span
                  class="flag-link mono"
                  @click="router.push(`/experiments/flags/${experiment.featureFlag!.id}`)"
                >
                  {{ experiment.featureFlag.key }} · {{ experiment.featureFlag.name }}
                </span>
              </div>
              <div v-if="detailsDirty" class="save-row">
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  icon="save"
                  :disabled="savingDetails"
                  @click="saveDetails">{{ savingDetails ? 'Saving…' : 'Save Details' }}</Button>
              </div>
            </div>
          </SectionCard>

          <!-- Attached Rule: variation names + share % + control + value -->
          <SectionCard padded title="Attached Rule">
            <FormField
              label="Control variation"
              help="The persisted baseline used for every aggregation, analysis, report, and rollout comparison. It can only change while the experiment is a draft."
            >
              <Select
                v-if="experiment.status === 'DRAFT'"
                :model-value="experiment.controlVariationKey"
                :options="controlVariationOptions"
                :disabled="savingControl"
                @update:model-value="updateControlVariation"
              />
              <div v-else class="field-inline">
                <Badge color="#5ec5ff">control</Badge>
                <span>{{ variationName(experiment.controlVariationKey) }}</span>
                <code class="mono">{{ experiment.controlVariationKey }}</code>
              </div>
            </FormField>
            <div v-if="attachedRule" class="rule-card">
              <div class="rule-meta">
                <span v-if="attachedRule.name" class="rule-name">{{ attachedRule.name }}</span>
                <code class="mono rule-id">{{ experiment.targetingRuleId?.slice(0, 8) }}</code>
              </div>
              <p v-if="attachedRule.description" class="rule-description">{{ attachedRule.description }}</p>
              <p class="rule-blurb">
                Observes the variations the rule's rollout splits across:
              </p>
              <div class="variations-list">
                <div
                  v-for="vw in involvedVariationWeights"
                  :key="vw.variationKey"
                  class="variation-row"
                  :class="{ 'is-control': vw.variationKey === controlVariationKey }"
                >
                  <div class="variation-share">
                    <span class="variation-share-pct mono tabular">{{ rolloutShare(vw.weight) }}</span>
                    <span class="variation-share-label">share</span>
                  </div>
                  <div class="variation-info">
                    <div class="variation-name-row">
                      <span class="variation-name">{{ variationName(vw.variationKey) }}</span>
                      <Badge v-if="vw.variationKey === controlVariationKey" color="#5ec5ff">control</Badge>
                      <code class="mono variation-key">{{ vw.variationKey }}</code>
                    </div>
                    <p class="variation-value">Returns <code class="mono">{{ variationValueDisplay(vw.variationKey) }}</code></p>
                  </div>
                </div>
              </div>
            </div>
            <div v-else class="rule-default">
              No targeting rule is attached. New experiments require a rule because
              the default-variation path has no allocation rollout.
            </div>
          </SectionCard>

          <!-- Activation gate shared by every outcome metric -->
          <SectionCard padded title="Activation Filter">
            <div class="form-stack">
              <FormField
                label="Require an activation event"
                help="Only assigned subjects with a matching event enter outcome calculations. Their first match becomes the start of the measurement window; raw assignment counts remain available for allocation checks."
              >
                <Switch v-model="activationEnabled" :accent="accent" />
              </FormField>

              <template v-if="activationEnabled">
                <div class="grid-2">
                  <FormField label="Event type">
                    <Select v-model="activationEventType" :options="ACTIVATION_EVENT_TYPE_OPTIONS" />
                  </FormField>
                  <FormField label="Element type" help="Use page for a page impression.">
                    <TextInput v-model="activationElementType" mono placeholder="page" />
                  </FormField>
                  <FormField label="Element ID">
                    <TextInput v-model="activationElementId" mono placeholder="(any)" />
                  </FormField>
                  <FormField label="Exact page path" help="Combined with the prefixes below as an alternative.">
                    <TextInput v-model="activationPagePath" mono placeholder="(any)" />
                  </FormField>
                </div>
                <FormField
                  label="Page path prefixes"
                  help="One prefix per line or comma-separated. For article activation, use the route prefix that identifies every article page."
                >
                  <Textarea
                    v-model="activationPagePathPrefixes"
                    :rows="3"
                    placeholder="/articles/"
                  />
                </FormField>
                <div class="grid-2">
                  <FormField label="Item extra key">
                    <TextInput v-model="activationItemExtraKey" mono placeholder="(optional)" />
                  </FormField>
                  <FormField
                    label="Match an exact item extra value"
                    help="Off matches any value when the key is present. On performs an exact, case-sensitive scalar match, including an empty string or surrounding whitespace."
                  >
                    <Switch v-model="activationItemExtraValueEnabled" :accent="accent" />
                  </FormField>
                  <FormField
                    v-if="activationItemExtraValueEnabled"
                    label="Exact item extra value"
                    help="The value is stored exactly as entered."
                  >
                    <TextInput v-model="activationItemExtraValue" mono placeholder="recommendations" />
                  </FormField>
                </div>
              </template>

              <div class="save-row">
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  icon="save"
                  :disabled="savingActivation"
                  @click="saveActivationFilter"
                >{{ savingActivation ? 'Saving…' : (activationEnabled ? 'Save Activation Filter' : 'Clear Activation Filter') }}</Button>
              </div>
            </div>
          </SectionCard>

          <!-- Statistical method editor -->
          <SectionCard padded title="Statistical Method">
            <div class="form-stack">
              <FormField
                label="Analysis method"
                help="Frequentist applies chi-squared / Welch's t with Bonferroni correction. Bayesian computes posterior probabilities — no peeking penalty, no family-wise correction."
              >
                <Select v-model="analysisMethodValue" :options="ANALYSIS_METHOD_OPTIONS" />
              </FormField>

              <template v-if="analysisMethodValue === 'BAYESIAN'">
                <p class="form-blurb">
                  Bayesian priors. Leave blank for sane defaults
                  (uniform <code>Beta(1, 1)</code> for proportions and an
                  improper flat Normal for per-user means).
                </p>
                <div class="grid-2">
                  <FormField label="Beta α (alpha)">
                    <NumberInput
                      v-model="bayesianBetaAlpha"
                      :min="0"
                      :step="0.1"
                      placeholder="1.0" />
                  </FormField>
                  <FormField label="Beta β (beta)">
                    <NumberInput
                      v-model="bayesianBetaBeta"
                      :min="0"
                      :step="0.1"
                      placeholder="1.0" />
                  </FormField>
                  <FormField label="Normal prior mean">
                    <NumberInput v-model="bayesianNormalMean" :step="0.1" placeholder="(no prior)" />
                  </FormField>
                  <FormField label="Normal prior variance">
                    <NumberInput
                      v-model="bayesianNormalVariance"
                      :min="0"
                      :step="0.1"
                      placeholder="(no prior)" />
                  </FormField>
                </div>
              </template>

              <div class="save-row">
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  icon="save"
                  :disabled="savingAnalysisMethod"
                  @click="saveAnalysisMethod">{{ savingAnalysisMethod ? 'Saving…' : 'Save Analysis Method' }}</Button>
              </div>
            </div>
          </SectionCard>

          <!-- Rollout policy editor -->
          <SectionCard padded title="Rollout Policy">
            <div class="form-stack">
              <FormField
                label="Enable automated rollout controller"
                help="When off, all rollout changes are manual. When on, a controller job runs after every analysis to apply the policy below."
              >
                <Switch v-model="rolloutPolicy.policyEnabled.value" :accent="accent" />
              </FormField>

              <template v-if="rolloutPolicy.policyEnabled.value">
                <FormField
                  label="Mode"
                  help="Manual: only halts on guardrail trips. Scheduled: time-driven steps. Adaptive Steps: advance one step per SHIP verdict. Adaptive Continuous: ramp by N% per SHIP verdict."
                >
                  <Select
                    v-model="rolloutPolicy.policyMode.value"
                    :options="rolloutPolicy.ROLLOUT_MODE_OPTIONS"
                  />
                </FormField>

                <FormField
                  label="Treatment variation"
                  help="The variation key the controller ramps toward. Must be served by the experiment's attached rule."
                >
                  <Select
                    v-model="rolloutPolicy.policyTreatmentVariationKey.value"
                    :options="treatmentVariationOptions"
                  />
                </FormField>

                <template
                  v-if="rolloutPolicy.policyMode.value === 'SCHEDULED_STEPS'
                    || rolloutPolicy.policyMode.value === 'ADAPTIVE_STEPS'"
                >
                  <FormField label="Steps">
                    <div class="steps-editor">
                      <div
                        v-for="(step, idx) in rolloutPolicy.policySteps.value"
                        :key="idx"
                        class="step-row"
                      >
                        <span class="step-label mono">Step {{ idx + 1 }}</span>
                        <NumberInput
                          v-model="step.weightPercent"
                          :min="0"
                          :max="100"
                          :step="1"
                        />
                        <span class="step-unit">%</span>
                        <TextInput
                          v-if="rolloutPolicy.policyMode.value === 'SCHEDULED_STEPS'"
                          v-model="step.afterDuration"
                          mono
                          placeholder="P1D"
                        />
                        <span v-if="rolloutPolicy.policyMode.value === 'SCHEDULED_STEPS'" class="step-unit">after previous</span>
                        <Button
                          size="xs"
                          icon="trash"
                          @click="rolloutPolicy.removePolicyStep(idx)"
                        />
                      </div>
                      <Button
                        size="xs"
                        icon="plus"
                        :accent="accent"
                        @click="rolloutPolicy.addPolicyStep">Add step</Button>
                    </div>
                  </FormField>
                </template>

                <FormField
                  v-if="rolloutPolicy.policyMode.value === 'ADAPTIVE_CONTINUOUS'"
                  label="Increment per SHIP verdict (percentage points)"
                  help="Each successful analysis cycle adds this many percentage points to the treatment weight, capped at 100."
                >
                  <NumberInput
                    v-model="rolloutPolicy.policyIncrementPercent.value"
                    :min="0.1"
                    :max="100"
                    :step="0.5"
                  />
                </FormField>

                <div class="grid-2">
                  <FormField
                    label="Min confidence to auto-advance"
                    help="The bar a winner must clear before adaptive modes promote. Frequentist: 1 - p-value. Bayesian: P(treatment > control)."
                  >
                    <NumberInput
                      v-model="rolloutPolicy.policyMinConfidence.value"
                      :min="0"
                      :max="1"
                      :step="0.01"
                    />
                  </FormField>
                  <FormField
                    label="Guardrail confidence threshold"
                    help="Confidence required to call a guardrail regression."
                  >
                    <NumberInput
                      v-model="rolloutPolicy.policyGuardrailThreshold.value"
                      :min="0"
                      :max="1"
                      :step="0.01"
                    />
                  </FormField>
                  <FormField
                    label="Guardrail min regression %"
                    help="Minimum negative lift magnitude (in percent) required to count as a guardrail regression."
                  >
                    <NumberInput
                      v-model="rolloutPolicy.policyGuardrailMinRegressionPercent.value"
                      :min="0"
                      :step="0.1"
                    />
                  </FormField>
                  <FormField
                    label="Halt on guardrail"
                    help="When on, a guardrail HALT verdict resets treatment to 0 and pauses the experiment. When off, the controller records a held event but does not modify the rollout (dry-run)."
                  >
                    <Switch v-model="rolloutPolicy.policyHaltOnGuardrail.value" :accent="accent" />
                  </FormField>
                </div>
              </template>

              <div class="save-row">
                <Button
                  size="sm"
                  primary
                  :accent="accent"
                  icon="save"
                  :disabled="rolloutPolicy.savingPolicy.value"
                  @click="rolloutPolicy.save">
                  {{ rolloutPolicy.savingPolicy.value
                    ? 'Saving…'
                    : (rolloutPolicy.policyEnabled.value ? 'Save Rollout Policy' : 'Clear Rollout Policy') }}
                </Button>
              </div>
            </div>
          </SectionCard>

          <!-- Conversion goals -->
          <SectionCard padded title="Conversion Goals">
            <template #right>
              <Button
                size="xs"
                icon="plus"
                :accent="accent"
                @click="goals.openAddGoal">Add Goal</Button>
            </template>

            <div v-if="experiment.conversionGoals.length === 0" class="empty-state">
              No conversion goals defined yet. Add at least one primary goal to drive ship/no-ship decisions.
            </div>
            <div v-else class="goals-list">
              <div
                v-for="goal in experiment.conversionGoals"
                :key="goal.id"
                class="goal-card"
                :class="{ 'is-guardrail': goal.role === 'GUARDRAIL' }"
              >
                <div class="goal-card-info">
                  <div class="goal-card-title-row">
                    <span class="goal-card-name">{{ goal.name }}</span>
                    <Badge :color="ROLE_COLORS[goal.role] ?? '#6c7388'">{{ goal.role.toLowerCase() }}</Badge>
                    <Badge v-if="goal.cupedCovariate" color="#5ec5ff">CUPED</Badge>
                  </div>
                  <p class="goal-card-filters">
                    Event: {{ goal.eventType || 'any' }}
                    <span v-if="goal.elementType"> · Element: <code class="mono">{{ goal.elementType }}</code></span>
                    <span v-if="goal.elementId"> · ID: <code class="mono">{{ goal.elementId }}</code></span>
                    <span v-if="goal.pagePath"> · Page: <code class="mono">{{ goal.pagePath }}</code></span>
                    <span v-if="goal.pagePathPrefixes?.length">
                      · Page prefixes: <code class="mono">{{ goal.pagePathPrefixes?.join(', ') }}</code>
                    </span>
                    <span v-if="goal.itemExtraKey">
                      · Item extra: <code class="mono">{{ goal.itemExtraKey }}</code><template v-if="goal.itemExtraValue != null">=<code class="mono">{{ JSON.stringify(goal.itemExtraValue) }}</code></template>
                    </span>
                    · Metric: {{ metricLabel(goal.metricType) }}
                  </p>
                </div>
                <div class="goal-card-actions">
                  <Button size="xs" icon="pencil" @click="goals.openEditGoal(goal)" />
                  <Button
                    size="xs"
                    icon="trash"
                    danger
                    @click="goals.requestDeleteGoal(goal)" />
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Results -->
          <SectionCard padded title="Results">
            <template #right>
              <span v-if="lastAggregatedAt" class="last-aggregated">Last aggregated {{ lastAggregatedAt }}</span>
            </template>

            <div v-if="resultsByGoal.length === 0" class="empty-state empty-state--block">
              <p class="empty-state-title">No results yet</p>
              <p>
                Click <strong>Aggregate</strong> above to compute results from the analytics
                events table. Results show one row per (variation, goal) combination with
                outcome-eligible subjects, raw assignments, conversion rate or per-user mean, statistical confidence, and
                percentage lift over the control variation.
              </p>
              <p>
                If you've already aggregated and still see this, it usually means either the
                experiment is not <code>RUNNING</code>, no events have landed in the warehouse
                for the experiment's time window, or the converting <code>client_id</code>s
                do not match any assignment in this experiment.
              </p>
              <Button
                size="sm"
                icon="pulse"
                :accent="accent"
                :disabled="aggregating"
                @click="onAggregate">{{ aggregating ? 'Aggregating…' : 'Aggregate now' }}</Button>
            </div>

            <div v-else class="results-groups">
              <div
                v-for="group in resultsByGoal"
                :key="group.goalId"
                class="goal-result-card"
                :class="{ 'is-guardrail': group.role === 'GUARDRAIL' }"
              >
                <div class="goal-result-header">
                  <div class="goal-result-title-row">
                    <span class="goal-result-name">{{ group.goalName }}</span>
                    <Badge :color="group.metricType === 'UNIQUE_CONVERSION' ? 'var(--brand-2)' : '#6c7388'">
                      {{ metricLabel(group.metricType) }}
                    </Badge>
                    <Badge :color="ROLE_COLORS[group.role] ?? '#6c7388'">{{ group.role.toLowerCase() }}</Badge>
                    <Badge v-if="group.cupedConfigured" color="#5ec5ff">CUPED</Badge>
                  </div>
                  <p class="goal-result-filter mono">Matches: {{ group.filterDescription }}</p>
                </div>

                <div v-if="duplicateGoalIds.has(group.goalId)" class="duplicate-warning">
                  <strong>Duplicate goal:</strong> another goal in this experiment has the same
                  filter and is producing identical numbers. Distinguish them by setting
                  different element/page/eventType filters, or delete one.
                </div>
                <div v-if="group.coverageImbalanced" class="duplicate-warning">
                  <strong>Uneven session coverage:</strong> the share of eligible subjects with stored session data differs by more than 10 percentage points between variations. This result is inconclusive.
                </div>
                <div v-if="group.broadImpressionGoal" class="duplicate-warning">
                  <strong>Broad impression filter:</strong> this counts every tracked element that became visible.
                  It is not a page-view count. Set <code>element.type</code> to <code>page</code> for page
                  impressions, or add an item-extra filter for a specific recommendation surface.
                </div>

                <!-- Per-goal empty-state distinguishing "no assignments" from "zero conversions". -->
                <div
                  v-if="group.totalImpressions === 0 && (group.metricType === 'SESSION_DURATION' ? group.totalObservations === 0 : group.totalConversions === 0)"
                  class="goal-empty-state"
                >
                  <template v-if="group.totalAssignments > 0">
                    <strong>{{ group.totalAssignments.toLocaleString() }} assigned subjects, none activated.</strong>
                    No assignment produced an event matching the experiment's activation filter.
                  </template>
                  <template v-else>
                    No assigned subjects yet — no users have been bucketed into the experiment's variations.
                  </template>
                </div>
                <div
                  v-else-if="group.metricType === 'SESSION_DURATION' ? group.totalObservations === 0 : group.totalConversions === 0"
                  class="goal-empty-state goal-empty-state--warn"
                >
                  <template v-if="group.metricType === 'SESSION_DURATION'">
                    <strong>{{ group.totalImpressions.toLocaleString() }} eligible subjects, no observed sessions.</strong>
                    No stored session spans matched an assigned subject in this experiment window.
                  </template>
                  <template v-else>
                    <strong>{{ group.totalImpressions.toLocaleString() }} eligible subjects, zero conversions.</strong>
                    Events matching the goal's filter never landed against any bucketed user.
                    Most common cause: the filter doesn't match the events you're firing —
                    loosen it (set <code>eventType</code> to <em>(any event type)</em> or clear
                    element/page constraints) and re-aggregate.
                  </template>
                </div>

                <template v-else>
                  <!-- Inline bar comparison: one bar per variation, scaled to the
                       metric column the operator actually cares about for this goal. -->
                  <div class="bar-chart">
                    <div v-for="row in group.rows" :key="row.variationKey" class="bar-row">
                      <span class="bar-label">{{ variationName(row.variationKey) }}</span>
                      <div class="bar-track">
                        <div
                          class="bar-fill"
                          :style="{
                            width: `${Math.min(100, Math.max(0, (((group.metricType === 'EVENT_COUNT' || group.metricType === 'SESSION_DURATION')
                              ? (row.mean ?? 0)
                              : (row.conversionRate ?? 0) * 100) / group.chartMax) * 100))}%`,
                            background: row.isControl ? '#5ec5ff' : accent,
                          }"
                        />
                      </div>
                      <span class="bar-value mono tabular">
                        {{ group.metricType === 'EVENT_COUNT' || group.metricType === 'SESSION_DURATION'
                          ? (row.mean != null ? `${row.mean.toFixed(3)}${group.unit === 'seconds' ? ' s' : ''}` : '—')
                          : `${((row.conversionRate ?? 0) * 100).toFixed(2)}%` }}
                      </span>
                    </div>
                  </div>

                  <div class="results-table">
                    <div class="results-header-row">
                      <span>Variation</span>
                      <span class="text-right">Eligible / assigned</span>
                      <span class="text-right">{{ group.metricType === 'SESSION_DURATION' ? 'Observations' : group.metricType === 'EVENT_COUNT' ? 'Total events' : 'Conversions' }}</span>
                      <span class="text-right">{{ group.metricType === 'SESSION_DURATION' ? 'Avg seconds' : group.metricType === 'EVENT_COUNT' ? 'Events / user' : 'Rate' }}</span>
                      <span class="text-right">vs. control</span>
                      <span class="text-right">Confidence</span>
                    </div>
                    <div
                      v-for="row in group.rows"
                      :key="row.variationKey"
                      class="results-data-row"
                      :class="{ 'is-control': row.isControl }"
                    >
                      <span class="row-variation">
                        <span class="variation-display-name">{{ variationName(row.variationKey) }}</span>
                        <Badge v-if="row.isControl" color="#5ec5ff">control</Badge>
                      </span>
                      <span class="mono tabular text-right">
                        {{ row.impressions.toLocaleString() }}
                        <span v-if="row.assignments !== row.impressions" class="muted">/ {{ row.assignments.toLocaleString() }}</span>
                      </span>
                      <span class="mono tabular text-right">
                        {{ (group.metricType === 'SESSION_DURATION' ? row.observationCount : row.conversions).toLocaleString() }}
                        <span v-if="group.metricType === 'SESSION_DURATION'" class="muted">{{ (row.coverage * 100).toFixed(1) }}%</span>
                      </span>
                      <span class="mono tabular text-right metric-cell">
                        <template v-if="group.metricType === 'EVENT_COUNT' || group.metricType === 'SESSION_DURATION'">
                          {{ row.mean != null ? row.mean.toFixed(2) : '—' }}
                          <span v-if="row.variance != null" class="muted">
                            ± {{ Math.sqrt(row.variance).toFixed(2) }}
                          </span>
                          <span v-if="row.cupedApplied && row.adjustedVariance != null" class="cuped-line">
                            CUPED ± {{ Math.sqrt(row.adjustedVariance).toFixed(2) }}
                          </span>
                        </template>
                        <template v-else>
                          {{ ((row.conversionRate ?? 0) * 100).toFixed(1) }}%
                        </template>
                      </span>
                      <span class="mono tabular text-right">
                        <span v-if="row.isControl" class="muted">—</span>
                        <span v-else-if="row.lift != null" :style="{ color: liftColor(row.lift), fontWeight: 500 }">
                          {{ formatLift(row.lift) }}
                        </span>
                        <span v-else class="muted">—</span>
                      </span>
                      <span class="text-right">
                        <Badge v-if="row.isControl" color="#6c7388">baseline</Badge>
                        <Badge
                          v-else-if="analysisMethodValue === 'BAYESIAN' && row.probabilityBeatsControl != null"
                          :color="confidenceColor(row.probabilityBeatsControl)"
                        >
                          P(win) {{ (row.probabilityBeatsControl * 100).toFixed(0) }}%
                        </Badge>
                        <Badge
                          v-else-if="row.confidenceLevel != null"
                          :color="confidenceColor(row.confidenceLevel)"
                        >
                          {{ (row.confidenceLevel * 100).toFixed(0) }}%
                        </Badge>
                        <span v-else class="muted">insufficient data</span>
                      </span>
                    </div>
                  </div>
                </template>
              </div>
            </div>
          </SectionCard>

          <!-- Analysis reports (deterministic + AI insights below) -->
          <SectionCard v-if="experiment.analysisReports.length > 0" padded title="Analysis Reports">
            <div class="reports-list">
              <div v-for="report in sortedAnalysisReports" :key="report.id" class="report-card">
                <div class="report-header">
                  <Badge v-if="!isCurrentAnalysisReport(report)" color="#6c7388">historical definition</Badge>
                  <Badge v-if="report.recommendation" :color="VERDICT_COLORS[report.recommendation] ?? '#6c7388'">
                    {{ report.recommendation.replace(/_/g, ' ') }}
                  </Badge>
                  <Badge v-if="report.confidence != null" :color="confidenceColor(report.confidence)">
                    {{ formatPct(report.confidence) }} confidence
                  </Badge>
                  <span class="report-date">{{ formatDateTime(report.created) }}</span>
                </div>
                <p v-if="report.summary" class="report-summary">{{ report.summary }}</p>

                <div v-if="report.aiInsights" class="ai-insights">
                  <div class="ai-insights-header">
                    <Badge color="#5ec5ff">AI-generated</Badge>
                    <span class="ai-insights-blurb">
                      Commentary on top of the deterministic verdict above — not authoritative.
                    </span>
                  </div>
                  <div v-if="report.aiInsights.hypothesisAssessment" class="insight-item">
                    <span class="insight-label">Hypothesis assessment</span>
                    <p class="insight-text">{{ report.aiInsights.hypothesisAssessment }}</p>
                  </div>
                  <div v-if="report.aiInsights.crossGoalPatterns" class="insight-item">
                    <span class="insight-label">Cross-goal patterns</span>
                    <p class="insight-text">{{ report.aiInsights.crossGoalPatterns }}</p>
                  </div>
                  <div v-if="report.aiInsights.followUpExperiments" class="insight-item">
                    <span class="insight-label">Follow-up experiments</span>
                    <ul v-if="Array.isArray(report.aiInsights.followUpExperiments)" class="insight-list">
                      <li v-for="(item, i) in report.aiInsights.followUpExperiments" :key="i">{{ item }}</li>
                    </ul>
                    <p v-else class="insight-text">{{ report.aiInsights.followUpExperiments }}</p>
                  </div>
                  <div v-if="report.aiInsights.srmRootCauseHints" class="insight-item">
                    <span class="insight-label">SRM root-cause hints</span>
                    <p class="insight-text">{{ report.aiInsights.srmRootCauseHints }}</p>
                  </div>
                </div>
              </div>
            </div>
          </SectionCard>

          <!-- Rollout policy events: every controller wake-up, including held -->
          <SectionCard v-if="experiment.rolloutPolicyEvents.length > 0" padded title="Rollout Events">
            <div class="events-timeline">
              <div v-for="evt in experiment.rolloutPolicyEvents" :key="evt.id" class="event-item">
                <Badge :color="ROLLOUT_ACTION_COLORS[evt.action] ?? '#6c7388'">{{ evt.action.toLowerCase() }}</Badge>
                <div class="event-content">
                  <p v-if="evt.reason" class="event-reason">{{ evt.reason }}</p>
                  <p class="event-weights mono">
                    {{ formatWeightSnapshot(evt.oldWeights) }}
                    <span class="event-arrow">→</span>
                    {{ formatWeightSnapshot(evt.newWeights) }}
                  </p>
                </div>
                <span class="event-date">{{ formatDateTime(evt.created) }}</span>
              </div>
            </div>
          </SectionCard>
        </div>

        <!-- Sidebar -->
        <div class="sidebar">
          <SectionCard padded title="Details">
            <div class="meta-grid">
              <div class="meta-item">
                <span class="meta-label">Status</span>
                <Badge :color="STATUS_COLORS[experiment.status] ?? '#6c7388'">{{ experiment.status }}</Badge>
              </div>
              <div v-if="experiment.featureFlag?.type" class="meta-item">
                <span class="meta-label">Flag type</span>
                <span class="meta-value">{{ experiment.featureFlag.type }}</span>
              </div>
              <div v-if="experiment.featureFlag" class="meta-item">
                <span class="meta-label">Flag key</span>
                <span class="meta-value mono">{{ experiment.featureFlag.key }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">Analysis</span>
                <span class="meta-value">{{ experiment.analysisMethod ?? 'FREQUENTIST' }}</span>
              </div>
              <div v-if="experiment.targetSampleSize" class="meta-item">
                <span class="meta-label">Target sample</span>
                <span class="meta-value mono">{{ experiment.targetSampleSize.toLocaleString() }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">Created</span>
                <span class="meta-value">{{ formatDate(experiment.created) }}</span>
              </div>
              <div class="meta-item">
                <span class="meta-label">Modified</span>
                <span class="meta-value">{{ formatDate(experiment.modified) }}</span>
              </div>
              <div v-if="experiment.startDate" class="meta-item">
                <span class="meta-label">Start date</span>
                <span class="meta-value">{{ formatDate(experiment.startDate) }}</span>
              </div>
              <div v-if="experiment.endDate" class="meta-item">
                <span class="meta-label">End date</span>
                <span class="meta-value">{{ formatDate(experiment.endDate) }}</span>
              </div>
            </div>
          </SectionCard>

          <SectionCard padded title="Exclusion Layer">
            <FormField
              label="Attached layer"
              help="Exclusion layers prevent overlap with concurrent experiments competing for the same users."
            >
              <Select
                v-model="selectedLayerValue"
                :options="layerOptions"
                :disabled="savingLayer"
              />
            </FormField>
            <div v-if="experiment.exclusionLayer" class="layer-link">
              <span
                class="flag-link"
                @click="router.push(`/experiments/layers/${experiment.exclusionLayer.id}`)"
              >View layer →</span>
            </div>
          </SectionCard>

          <SectionCard padded title="Excluded Accounts">
            <p class="excluded-accounts-summary">
              {{ experiment.excludedPrincipalIds.length.toLocaleString() }}
              {{ experiment.excludedPrincipalIds.length === 1 ? 'account is' : 'accounts are' }} excluded from calculations.
            </p>
            <Button
              size="sm"
              icon="users"
              @click="router.push(`/experiments/exp/exclusions/${experiment.id}`)"
            >Manage accounts</Button>
          </SectionCard>
        </div>
      </div>
    </template>

    <!-- Goal modal -->
    <Modal
      v-if="goals.goalModalOpen.value"
      :title="goals.editingGoalId.value ? 'Edit Conversion Goal' : 'Add Conversion Goal'"
      icon="target"
      :accent="accent"
      width="600px"
      @close="goals.goalModalOpen.value = false"
    >
      <div class="goal-form">
        <FormField label="Name">
          <TextInput v-model="goals.goalName.value" placeholder="Signup Completed" />
        </FormField>
        <FormField
          label="Metric Type"
          help="Unique Conversion counts eligible subjects who act. Event Count averages matching follow-up events over eligible subjects. Session Duration includes observed audio/video progress. Older play events estimate remaining media duration, updated by pause, seek, end, or error events. The five-minute inactivity timeout follows the last activity or playback."
        >
          <Select v-model="goals.goalMetricType.value" :options="goals.METRIC_TYPE_OPTIONS" />
        </FormField>
        <template v-if="goals.goalMetricType.value !== 'SESSION_DURATION'">
          <FormField
            label="Event Type"
            help="Choose a single event type, or '(any event type)' to count every event a user emits."
          >
            <Select v-model="goals.goalEventType.value" :options="goals.EVENT_TYPE_OPTIONS" />
          </FormField>
          <FormField label="Element Type" help="Leave blank to match elements of any type.">
            <TextInput v-model="goals.goalElementType.value" mono placeholder="button" />
          </FormField>
          <FormField label="Element ID" help="Leave blank to match any element ID.">
            <TextInput v-model="goals.goalElementId.value" mono placeholder="signup-btn" />
          </FormField>
          <FormField
            label="Page Path"
            help="Restricts the goal to events emitted on this page (matches window.location.pathname). Leave blank to count events from any page."
          >
            <TextInput v-model="goals.goalPagePath.value" mono placeholder="/checkout" />
          </FormField>
          <FormField
            label="Page Path Prefixes"
            help="One prefix per line or comma-separated. A match on the exact path above or any prefix qualifies. Use content route prefixes to count follow-up content-page impressions across talks, articles, studies, and other content types."
          >
            <Textarea
              v-model="goals.goalPagePathPrefixes.value"
              :rows="3"
              placeholder="/articles/&#10;/talks/&#10;/studies/"
            />
          </FormField>
          <FormField
            label="Item extra key"
            help="Stored snake_case analytics extra key. Browser HTML uses data-ba-kebab-case; for example data-ba-experiment-source becomes experiment_source. A matching event must also carry a content reference."
          >
            <TextInput v-model="goals.goalItemExtraKey.value" mono placeholder="experiment_source" />
          </FormField>
          <FormField
            label="Match an exact item extra value"
            help="Off matches any value when the key is present. On performs an exact, case-sensitive scalar match, including an empty string or surrounding whitespace."
          >
            <Switch v-model="goals.goalItemExtraValueEnabled.value" :accent="accent" />
          </FormField>
          <FormField
            v-if="goals.goalItemExtraValueEnabled.value"
            label="Exact item extra value"
            help="The value is stored exactly as entered."
          >
            <TextInput v-model="goals.goalItemExtraValue.value" mono placeholder="recommendations" />
          </FormField>
        </template>
        <FormField
          label="Role"
          help="Primary metrics drive ship/no-ship. Secondary metrics are informational. Guardrails trigger HALT and immediate rollback when they regress — use them for safety metrics like error rate or page-load time."
        >
          <Select v-model="goals.goalRole.value" :options="goals.ROLE_OPTIONS" />
        </FormField>

        <!-- CUPED variance reduction — only meaningful for EVENT_COUNT goals. -->
        <template v-if="goals.goalMetricType.value === 'EVENT_COUNT'">
          <FormField
            label="CUPED variance reduction"
            help="Subtracts the part of each user's post-period outcome that their pre-period activity already explained. Typical sample-size reduction is 30–50% on metrics that correlate with pre-period activity."
          >
            <Switch v-model="goals.cupedEnabled.value" :accent="accent" label="Enable CUPED for this goal" />
          </FormField>
          <template v-if="goals.cupedEnabled.value">
            <FormField label="Covariate event type" help="Defaults to the goal's own event type.">
              <Select v-model="goals.cupedEventType.value" :options="goals.EVENT_TYPE_OPTIONS" />
            </FormField>
            <FormField label="Covariate element type">
              <TextInput v-model="goals.cupedElementType.value" mono placeholder="(inherits goal)" />
            </FormField>
            <FormField label="Covariate element ID">
              <TextInput v-model="goals.cupedElementId.value" mono placeholder="(inherits goal)" />
            </FormField>
            <FormField label="Covariate page path">
              <TextInput v-model="goals.cupedPagePath.value" mono placeholder="(inherits goal)" />
            </FormField>
            <FormField
              label="Lookback window"
              help="ISO-8601 duration. P14D = 14 days, P30D = 30 days, PT168H = 168 hours."
            >
              <TextInput v-model="goals.cupedLookbackWindow.value" mono placeholder="P14D" />
            </FormField>
          </template>
        </template>
      </div>
      <template #footer>
        <span class="spacer" />
        <Button size="sm" @click="goals.goalModalOpen.value = false">Cancel</Button>
        <Button
          size="sm"
          primary
          :accent="accent"
          :disabled="goals.goalSaving.value || !goals.goalName.value.trim()"
          @click="goals.saveGoal">
          {{ goals.goalSaving.value ? 'Saving…' : goals.editingGoalId.value ? 'Update' : 'Add' }}
        </Button>
      </template>
    </Modal>

    <!-- Goal delete confirmation — warns about historical results cascade. -->
    <ConfirmModal
      v-if="goals.goalDeleteTarget.value"
      :title="`Delete '${goals.goalDeleteTarget.value.name}'?`"
      message="All previously aggregated results for this goal will be removed by cascade. Re-running aggregation later will NOT restore them — historical readings for this goal will be gone."
      :loading="goals.goalDeleteLoading.value"
      @close="goals.goalDeleteTarget.value = null"
      @confirm="goals.confirmDeleteGoal"
    />
  </PageShell>
</template>

<style scoped>
.loading-state,
.error-state {
  padding: 40px;
  text-align: center;
  color: var(--fg-3);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}

/* Verdict banner — loud border-left color, dominates a SHIP under a HALT. */
.verdict-banner {
  background: var(--bg-1);
  border: 1px solid var(--line);
  border-left: 4px solid;
  border-radius: var(--r-sm);
  padding: 14px 18px;
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.verdict-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.verdict-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--fg-3);
}

.verdict-confidence {
  font-size: 12px;
  color: var(--fg-3);
}

.verdict-summary {
  margin: 0;
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.5;
}

.verdict-recommendation {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-2);
  font-style: italic;
}

/* Two-column layout */
.detail-layout {
  display: grid;
  grid-template-columns: 1fr 300px;
  gap: 18px;
  align-items: start;
}

.main-content,
.sidebar {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

/* Overview */
.status-actions {
  display: flex;
  gap: 6px;
}

.start-blocked-message {
  max-width: 320px;
  margin: 8px 0 0;
  color: var(--fg-3);
  font-size: 11.5px;
  line-height: 1.4;
}

.overview-fields {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.field-inline {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}

.field-inline-label {
  font-size: 12px;
  color: var(--fg-3);
  font-weight: 500;
}

.flag-link {
  color: var(--brand-2);
  cursor: pointer;
}

.flag-link:hover {
  text-decoration: underline;
}

.save-row {
  display: flex;
  justify-content: flex-end;
}

/* Attached rule */
.rule-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
}

.rule-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rule-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.rule-id {
  font-size: 11px;
  color: var(--fg-3);
}

.rule-description {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--fg-2);
  font-style: italic;
}

.rule-blurb {
  margin: 10px 0 8px;
  font-size: 12px;
  color: var(--fg-3);
}

.rule-default {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
  font-size: 12.5px;
  color: var(--fg-2);
}

.variations-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.variation-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
}

.variation-row.is-control {
  border-color: color-mix(in srgb, #5ec5ff 50%, transparent);
  background: color-mix(in srgb, #5ec5ff 6%, var(--bg-1));
}

.variation-share {
  min-width: 60px;
  display: flex;
  flex-direction: column;
  align-items: center;
  padding: 6px 8px;
  border-radius: var(--r-sm);
  background: var(--bg-3);
}

.variation-share-pct {
  font-size: 15px;
  font-weight: 600;
  color: var(--fg-0);
}

.variation-share-label {
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--fg-3);
}

.variation-info {
  flex: 1;
  min-width: 0;
}

.variation-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.variation-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.variation-key {
  font-size: 11px;
  color: var(--fg-3);
}

.variation-value {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--fg-3);
}

/* Form layouts */
.form-stack {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.form-blurb {
  margin: 0;
  font-size: 12px;
  color: var(--fg-3);
  line-height: 1.5;
}

.form-blurb code,
.rule-default code {
  background: var(--bg-3);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 11px;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

/* Rollout policy steps editor */
.steps-editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.step-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.step-label {
  font-size: 11px;
  color: var(--fg-3);
  min-width: 48px;
}

.step-unit {
  font-size: 11px;
  color: var(--fg-3);
}

/* Conversion goals */
.goals-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.goal-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
}

.goal-card.is-guardrail {
  border-color: color-mix(in srgb, #f59e42 50%, transparent);
  background: color-mix(in srgb, #f59e42 5%, var(--bg-1));
}

.goal-card-info {
  flex: 1;
  min-width: 0;
}

.goal-card-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.goal-card-name {
  font-size: 13px;
  font-weight: 500;
  color: var(--fg-0);
}

.goal-card-filters {
  margin: 4px 0 0;
  font-size: 11.5px;
  color: var(--fg-3);
}

.goal-card-filters code {
  font-size: 10.5px;
  background: var(--bg-3);
  padding: 0 4px;
  border-radius: 3px;
}

.goal-card-actions {
  display: flex;
  gap: 4px;
}

/* Results */
.last-aggregated {
  font-size: 11.5px;
  color: var(--fg-3);
}

.results-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.goal-result-card {
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  overflow: hidden;
}

.goal-result-card.is-guardrail {
  border-color: color-mix(in srgb, #f59e42 50%, transparent);
}

.goal-result-header {
  padding: 12px 14px;
  border-bottom: 1px solid var(--line);
  background: var(--bg-2);
}

.goal-result-card.is-guardrail .goal-result-header {
  background: color-mix(in srgb, #f59e42 8%, var(--bg-2));
}

.goal-result-title-row {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.goal-result-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--fg-0);
}

.goal-result-filter {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--fg-3);
}

.duplicate-warning {
  margin: 12px 14px 0;
  padding: 10px 12px;
  background: color-mix(in srgb, #f59e42 10%, var(--bg-1));
  border: 1px solid color-mix(in srgb, #f59e42 35%, transparent);
  border-radius: var(--r-sm);
  font-size: 12px;
  color: var(--fg-1);
  line-height: 1.5;
}

.goal-empty-state {
  padding: 16px;
  font-size: 12.5px;
  color: var(--fg-3);
  text-align: center;
  line-height: 1.5;
}

.goal-empty-state--warn {
  color: var(--fg-1);
}

.goal-empty-state code {
  background: var(--bg-3);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 11px;
}

.bar-chart {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid var(--line);
}

.bar-row {
  display: grid;
  grid-template-columns: 140px 1fr 80px;
  align-items: center;
  gap: 10px;
}

.bar-label {
  font-size: 12px;
  color: var(--fg-1);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.bar-track {
  height: 8px;
  background: var(--bg-3);
  border-radius: 4px;
  overflow: hidden;
}

.bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.2s ease;
}

.bar-value {
  font-size: 12px;
  color: var(--fg-1);
  text-align: right;
}

.results-table {
  display: flex;
  flex-direction: column;
}

.results-header-row,
.results-data-row {
  display: grid;
  grid-template-columns: minmax(120px, 1fr) 110px 110px 130px 100px 110px;
  gap: 8px;
  padding: 10px 14px;
  align-items: center;
}

.results-header-row {
  font-size: 10.5px;
  color: var(--fg-3);
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 42%, transparent);
  background: var(--bg-2);
}

.results-data-row {
  font-size: 13px;
  border-bottom: 1px solid color-mix(in oklch, var(--line) 30%, transparent);
}

.results-data-row:last-child {
  border-bottom: none;
}

.results-data-row.is-control {
  background: color-mix(in srgb, #5ec5ff 4%, transparent);
}

.row-variation {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.variation-display-name {
  font-weight: 500;
  color: var(--fg-0);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.text-right { text-align: right; }
.muted { color: var(--fg-3); font-size: 11px; }

.metric-cell .muted {
  margin-left: 4px;
}

.cuped-line {
  display: block;
  color: #5ec5ff;
  font-size: 10.5px;
  margin-top: 2px;
}

/* Reports */
.reports-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.report-card {
  background: var(--bg-2);
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  padding: 14px;
}

.report-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.report-date {
  font-size: 11px;
  color: var(--fg-3);
  margin-left: auto;
}

.report-summary {
  font-size: 13px;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
}

.ai-insights {
  margin-top: 12px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--line);
}

.ai-insights-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-insights-blurb {
  font-size: 11.5px;
  color: var(--fg-3);
}

.insight-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.insight-label {
  font-size: 10.5px;
  font-weight: 600;
  color: var(--fg-3);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.insight-text {
  font-size: 12.5px;
  color: var(--fg-1);
  line-height: 1.5;
  margin: 0;
}

.insight-list {
  font-size: 12.5px;
  color: var(--fg-1);
  margin: 0;
  padding-left: 18px;
  line-height: 1.5;
}

/* Rollout events */
.events-timeline {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.event-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-1);
}

.event-content {
  flex: 1;
  min-width: 0;
}

.event-reason {
  margin: 0;
  font-size: 12.5px;
  color: var(--fg-1);
}

.event-weights {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--fg-3);
  word-break: break-all;
}

.event-arrow {
  margin: 0 4px;
  color: var(--fg-4);
}

.event-date {
  font-size: 11px;
  color: var(--fg-3);
  white-space: nowrap;
  margin-top: 2px;
}

/* Sidebar */
.meta-grid {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.meta-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-label {
  font-size: 12px;
  color: var(--fg-3);
  font-weight: 500;
}

.meta-value {
  font-size: 12px;
  color: var(--fg-1);
  text-align: right;
  word-break: break-all;
}

.layer-link {
  margin-top: 8px;
  font-size: 12px;
}

.excluded-accounts-summary {
  margin: 0 0 10px;
  color: var(--fg-3);
  font-size: 12px;
  line-height: 1.5;
}

/* Goal modal form */
.goal-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* Shared */
.empty-state {
  font-size: 13px;
  color: var(--fg-3);
  padding: 16px 0;
  text-align: center;
}

.empty-state--block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 30px 20px;
  border: 1px dashed var(--line);
  border-radius: var(--r-sm);
  background: var(--bg-2);
  line-height: 1.5;
}

.empty-state-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--fg-1);
  margin: 0;
}

.empty-state code {
  background: var(--bg-3);
  padding: 1px 5px;
  border-radius: 3px;
  font-size: 11px;
}

.spacer {
  flex: 1;
}
</style>
