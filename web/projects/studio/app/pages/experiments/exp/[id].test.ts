import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import ExperimentDetail from './[id].vue'

const refresh = vi.fn()
const query = vi.fn()
const mutation = vi.fn().mockResolvedValue({})
const toastError = vi.fn()
const routerPush = vi.fn()
const experimentData = ref<Record<string, unknown> | null>(null)

vi.stubGlobal('useRoute', () => ({ params: { id: 'exp-1' } }))
vi.stubGlobal('useRouter', () => ({ push: routerPush }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: toastError, info: vi.fn(), warn: vi.fn() }))
vi.stubGlobal('useGraphQL', () => ({
  mutation,
  query,
  useAsyncQuery: (key: string) => key === 'experiment-detail'
    ? { data: experimentData, status: ref('success'), refresh }
    : { data: ref({ exclusionLayers: { all: [] } }), status: ref('success'), refresh: vi.fn() },
}))

const passthrough = {
  template: '<div><slot name="header" /><slot name="actions" /><slot name="right" /><slot /><slot name="footer" /></div>',
}
const stubs = {
  PageShell: passthrough,
  PageHeader: passthrough,
  SectionCard: passthrough,
  FormField: { ...passthrough, name: 'FormField', props: ['label', 'help'] },
  Badge: { template: '<span><slot /></span>' },
  Button: { template: '<button><slot /></button>' },
  Modal: passthrough,
  ConfirmModal: { template: '<div />' },
  TextInput: { template: '<input />' },
  Textarea: { template: '<textarea />' },
  NumberInput: { template: '<input type="number" />' },
  Select: {
    name: 'Select',
    props: ['modelValue', 'options', 'onSearch', 'multiple'],
    emits: ['update:modelValue'],
    template: '<div />',
  },
  Switch: {
    name: 'Switch',
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input type="checkbox" :checked="modelValue" @change="$emit(\'update:modelValue\', $event.target.checked)" />',
  },
}

interface ReportFixture {
  id: string
  summary: string
  recommendation: string
  confidence: number
  details: Record<string, unknown>
  aiInsights: null
  created: string
}

function fixture(
  treatmentObservationCount = 70,
  options: {
    controlVariationKey?: string
    policyTreatmentKey?: string | null
    targetingRuleId?: string | null
    reports?: ReportFixture[]
    status?: string
    ruleVariationKeys?: string[]
    goalEventType?: string | null
    goalMetricType?: string
    activationFilter?: {
      eventType: string | null
      elementType: string | null
      elementId: string | null
      pagePath: string | null
      pagePathPrefixes: string[]
      itemExtraKey: string | null
      itemExtraValue: string | null
    } | null
    secondGoal?: {
      eventType: string | null
      metricType?: string
    }
  } = {},
) {
  const goal = {
    id: 'goal-1',
    name: 'Engaged session',
    eventType: options.goalEventType ?? null,
    elementType: null,
    elementId: null,
    metricType: options.goalMetricType ?? 'SESSION_DURATION',
    pagePath: null,
    pagePathPrefixes: [] as string[],
    itemExtraKey: null,
    itemExtraValue: null,
    role: 'PRIMARY',
    cupedCovariate: null,
  }
  const secondGoal = options.secondGoal
    ? {
        ...goal,
        id: 'goal-2',
        name: 'Second goal',
        eventType: options.secondGoal.eventType,
        metricType: options.secondGoal.metricType ?? goal.metricType,
      }
    : null
  const resultsForGoal = (goalDefinition: typeof goal, suffix: string) => [
    {
      id: `result-control${suffix}`, variationKey: 'control', goalId: goalDefinition.id, goal: goalDefinition,
      assignments: 100, impressions: 100, observationCount: 80, conversions: 0, conversionRate: 0,
      confidenceLevel: null, liftOverControl: null, mean: 10, variance: 4,
      probabilityBeatsControl: null, expectedLoss: null,
      adjustedMean: null, adjustedVariance: null, updatedAt: '2026-08-28T00:00:00Z',
    },
    {
      id: `result-treatment${suffix}`, variationKey: 'treatment', goalId: goalDefinition.id, goal: goalDefinition,
      assignments: 100, impressions: 100, observationCount: treatmentObservationCount, conversions: 0, conversionRate: 0,
      confidenceLevel: 0.9, liftOverControl: 20, mean: 12, variance: 5,
      probabilityBeatsControl: null, expectedLoss: null,
      adjustedMean: null, adjustedVariance: null, updatedAt: '2026-08-28T00:00:00Z',
    },
  ]
  return {
    experiments: {
      experiment: {
        id: 'exp-1',
        name: 'Session experiment',
        description: '',
        hypothesis: '',
        status: options.status ?? 'DRAFT',
        startDate: null,
        endDate: null,
        targetSampleSize: null,
        created: '2026-08-28T00:00:00Z',
        modified: '2026-08-28T00:00:00Z',
        featureFlagId: 'flag-1',
        targetingRuleId: options.targetingRuleId === undefined ? 'rule-1' : options.targetingRuleId,
        controlVariationKey: options.controlVariationKey ?? 'control',
        excludedPrincipalIds: [] as string[],
        activationFilter: options.activationFilter ?? null,
        featureFlag: {
          id: 'flag-1',
          key: 'session-flag',
          name: 'Session flag',
          type: 'BOOLEAN',
          variations: [
            { key: 'control', name: 'Control', value: false },
            { key: 'treatment', name: 'Treatment', value: true },
          ],
          targetingRules: [{
            id: 'rule-1',
            conditions: [],
            rollout: {
              variationWeights: (options.ruleVariationKeys ?? ['control', 'treatment'])
                .map(variationKey => ({ variationKey, weight: 50 })),
            },
          }],
        },
        exclusionLayer: null,
        analysisMethod: 'FREQUENTIST',
        bayesianPrior: null,
        rolloutPolicy: options.policyTreatmentKey === undefined
          ? null
          : {
              mode: 'ADAPTIVE_STEPS',
              treatmentVariationKey: options.policyTreatmentKey,
              steps: [],
              minConfidence: 0.95,
              haltOnGuardrail: true,
            },
        rolloutPolicyEvents: [],
        conversionGoals: secondGoal ? [goal, secondGoal] : [goal],
        results: [
          ...resultsForGoal(goal, ''),
          ...(secondGoal ? resultsForGoal(secondGoal, '-second') : []),
        ],
        analysisReports: options.reports ?? [],
      },
    },
  }
}

function mountPage() {
  return mount(ExperimentDetail, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...values: string[]) => values },
    },
  })
}

enableAutoUnmount(afterEach)

describe('Experiment detail results', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    experimentData.value = fixture()
  })

  it('links account exclusions to their dedicated page', async () => {
    const data = fixture()
    data.experiments.experiment.excludedPrincipalIds = ['principal-1']
    experimentData.value = data
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('1 account is excluded')
    await wrapper.findAll('button').find(button => button.text() === 'Manage accounts')!.trigger('click')
    expect(routerPush).toHaveBeenCalledWith('/experiments/exp/exclusions/exp-1')
  })

  it('preserves a content-page activation filter when saving it', async () => {
    experimentData.value = fixture(70, {
      activationFilter: {
        eventType: 'Impression',
        elementType: 'page',
        elementId: null,
        pagePath: null,
        pagePathPrefixes: ['/articles/'],
        itemExtraKey: null,
        itemExtraValue: null,
      },
    })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'Save Activation Filter')!.trigger('click')

    expect(mutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      id: 'exp-1',
      experiment: expect.objectContaining({
        activationFilter: {
          eventType: 'Impression',
          elementType: 'page',
          elementId: null,
          pagePath: null,
          pagePathPrefixes: ['/articles/'],
          itemExtraKey: null,
          itemExtraValue: null,
        },
      }),
    }))
  })

  it.each([null, 'Completion'])('preserves nullable activation selectors when saving %s events', async (eventType) => {
    const activationFilter = {
      eventType,
      elementType: null,
      elementId: 'play',
      pagePath: null,
      pagePathPrefixes: [],
      itemExtraKey: null,
      itemExtraValue: null,
    }
    experimentData.value = fixture(70, { activationFilter })
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'Save Activation Filter')!.trigger('click')

    expect(mutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      experiment: expect.objectContaining({ activationFilter }),
    }))
  })

  it.each([null, '', ' recommendations '])('preserves exact activation extra values when saving %s', async (itemExtraValue) => {
    const activationFilter = {
      eventType: 'Impression', elementType: 'page', elementId: null, pagePath: null,
      pagePathPrefixes: ['/articles/'], itemExtraKey: 'recommendation_source', itemExtraValue,
    }
    experimentData.value = fixture(70, { activationFilter })
    const wrapper = mountPage()
    await flushPromises()
    const field = wrapper.findAllComponents({ name: 'FormField' })
      .find(field => field.props('label') === 'Match an exact item extra value')!
    expect(field.findComponent({ name: 'Switch' }).props('modelValue')).toBe(itemExtraValue != null)

    await wrapper.findAll('button').find(button => button.text() === 'Save Activation Filter')!.trigger('click')

    expect(mutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      experiment: expect.objectContaining({ activationFilter }),
    }))
  })

  it('lets activation extras switch between key presence and an exact empty string', async () => {
    const activationFilter = {
      eventType: 'Impression', elementType: 'page', elementId: null, pagePath: null,
      pagePathPrefixes: ['/articles/'], itemExtraKey: 'recommendation_source', itemExtraValue: null,
    }
    experimentData.value = fixture(70, { activationFilter })
    const wrapper = mountPage()
    await flushPromises()
    const field = wrapper.findAllComponents({ name: 'FormField' })
      .find(field => field.props('label') === 'Match an exact item extra value')!
    for (const enabled of [true, false]) {
      await field.find('input').setValue(enabled)
      await wrapper.findAll('button').find(button => button.text() === 'Save Activation Filter')!.trigger('click')
      await flushPromises()
      expect(mutation).toHaveBeenLastCalledWith(expect.anything(), expect.objectContaining({
        experiment: expect.objectContaining({
          activationFilter: { ...activationFilter, itemExtraValue: enabled ? '' : null },
        }),
      }))
    }
  })

  it('keeps unsaved activation changes out of unrelated saves', async () => {
    const activationFilter = {
      eventType: 'Completion', elementType: null, elementId: 'play', pagePath: null,
      pagePathPrefixes: [], itemExtraKey: null, itemExtraValue: null,
    }
    experimentData.value = fixture(70, { activationFilter })
    const wrapper = mountPage()
    await flushPromises()
    const activationSelect = wrapper.findAllComponents({ name: 'Select' })
      .find(select => select.props('modelValue') === 'Completion')!
    activationSelect.vm.$emit('update:modelValue', 'Impression')
    await flushPromises()

    await wrapper.findAll('button').find(button => button.text() === 'Save Analysis Method')!.trigger('click')

    expect(mutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      experiment: expect.objectContaining({ activationFilter }),
    }))
  })

  it('only warns for impression goals without an element or item selector', async () => {
    const data = fixture(70, { goalEventType: 'Impression', goalMetricType: 'EVENT_COUNT' })
    experimentData.value = data
    const wrapper = mountPage()
    await flushPromises()
    expect(wrapper.text()).toContain('Broad impression filter:')
    const selected = fixture(70, { goalEventType: 'Impression', goalMetricType: 'EVENT_COUNT' })
    Object.assign(selected.experiments.experiment.conversionGoals[0]!, { itemExtraKey: 'recommendation_location' })
    experimentData.value = selected
    await flushPromises()
    expect(wrapper.text()).not.toContain('Broad impression filter:')
  })

  it.each([
    { pagePath: '/featured', pagePathPrefixes: ['/articles/', '/talks/'], description: '(page.path=/featured OR page.path starts with one of [/articles/, /talks/])' },
    { pagePath: '/featured', pagePathPrefixes: [], description: 'page.path=/featured' },
    { pagePath: null, pagePathPrefixes: ['/articles/'], description: 'page.path starts with one of [/articles/]' },
    { pagePath: null, pagePathPrefixes: [], description: '' },
  ])('describes page alternatives as "$description"', async ({ pagePath, pagePathPrefixes, description }) => {
    const data = fixture(70, { goalEventType: 'Impression', goalMetricType: 'EVENT_COUNT' })
    Object.assign(data.experiments.experiment.conversionGoals[0]!, {
      elementType: 'page', pagePath, pagePathPrefixes, itemExtraKey: 'campaign',
    })
    experimentData.value = data
    const wrapper = mountPage()
    await flushPromises()

    const expected = ['type=Impression', 'element.type=page', description, 'item.extra.campaign present']
      .filter(Boolean).join(' ∧ ')
    expect(wrapper.text()).toContain(`Matches: ${expected}`)
    expect(wrapper.text()).not.toContain('page.path=/featured ∧ page.path starts with')
  })

  it('renders nonzero session observations even when conversions are zero', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Session Duration')
    expect(wrapper.text()).toContain('Observations')
    expect(wrapper.text()).toContain('10.000 s')
    expect(wrapper.text()).not.toContain('zero conversions')
    expect(wrapper.text()).not.toContain('Uneven session coverage')
  })

  it('renders the coverage warning only above the exact ten-point boundary', async () => {
    experimentData.value = fixture(69)
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Uneven session coverage')
  })

  it('does not flag goals with distinct event filters as duplicates when their results match', async () => {
    experimentData.value = fixture(70, {
      goalEventType: 'Interaction',
      goalMetricType: 'UNIQUE_CONVERSION',
      secondGoal: { eventType: 'Completion' },
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('type=Interaction')
    expect(wrapper.text()).toContain('type=Completion')
    expect(wrapper.text()).not.toContain('Duplicate goal:')
  })

  it('flags goals with identical definitions and results as duplicates', async () => {
    experimentData.value = fixture(70, {
      goalEventType: 'Interaction',
      goalMetricType: 'UNIQUE_CONVERSION',
      secondGoal: { eventType: 'Interaction' },
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Duplicate goal:')
  })

  it('does not present Start for a legacy draft without a targeting rule', async () => {
    experimentData.value = fixture(70, { targetingRuleId: null })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(wrapper.text()).toContain('Recreate it with an attached rule')
  })

  it('does not present Start when the stored targeting rule no longer exists', async () => {
    experimentData.value = fixture(70, { targetingRuleId: 'removed-rule' })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(wrapper.text()).toContain('targeting rule that no longer exists')
  })

  it('does not present Start when the attached rule serves only one variation', async () => {
    experimentData.value = fixture(70, { ruleVariationKeys: ['control'] })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(wrapper.text()).toContain('serves fewer than two variations')
  })

  it('presents Start when the rule serves distinct control and treatment variations', async () => {
    experimentData.value = fixture(70, { policyTreatmentKey: 'treatment' })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === 'Start')).toBe(true)
  })

  it('does not present Start when the stored control is absent from the attached rule', async () => {
    experimentData.value = fixture(70, {
      controlVariationKey: 'retired-control',
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(wrapper.text()).toContain('control is no longer served')
  })

  it('does not present Start when the rollout treatment is absent or equals control', async () => {
    experimentData.value = fixture(70, { policyTreatmentKey: 'retired-treatment' })
    const missingWrapper = mountPage()
    await flushPromises()
    expect(missingWrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(missingWrapper.text()).toContain('treatment is no longer served')

    missingWrapper.unmount()
    experimentData.value = fixture(70, { policyTreatmentKey: 'control' })
    const conflictingWrapper = mountPage()
    await flushPromises()
    expect(conflictingWrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(conflictingWrapper.text()).toContain('treatment must differ')

    conflictingWrapper.unmount()
    experimentData.value = fixture(70, { policyTreatmentKey: '' })
    const blankWrapper = mountPage()
    await flushPromises()
    expect(blankWrapper.findAll('button').some(button => button.text() === 'Start')).toBe(false)
    expect(blankWrapper.text()).toContain('treatment is no longer served')
  })

  it('ignores reports from an earlier experiment definition revision', async () => {
    experimentData.value = fixture(70, {
      reports: [{
        id: 'report-stale',
        summary: 'Stale report summary',
        recommendation: 'SHIP',
        confidence: 0.99,
        details: {
          verdict: 'SHIP',
          controlVariationKey: 'control',
          experimentRevision: 6,
          isCurrent: false,
        },
        aiInsights: null,
        created: '2026-08-28T01:00:00Z',
      }],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Stale report summary')
    expect(wrapper.text()).not.toContain('Latest verdict')
  })

  it('renders a report produced from the current experiment definition', async () => {
    experimentData.value = fixture(70, {
      reports: [{
        id: 'report-current',
        summary: 'Current report summary',
        recommendation: 'SHIP',
        confidence: 0.99,
        details: {
          verdict: 'SHIP',
          controlVariationKey: 'control',
          experimentRevision: 7,
          isCurrent: true,
        },
        aiInsights: null,
        created: '2026-08-28T01:00:00Z',
      }],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Current report summary')
    expect(wrapper.text()).toContain('Latest verdict')
  })

  it('keeps the report that caused a pause presented as current', async () => {
    experimentData.value = fixture(70, {
      status: 'PAUSED',
      reports: [{
        id: 'report-halt',
        summary: 'Guardrail regression caused an automatic pause',
        recommendation: 'HALT',
        confidence: 0.99,
        details: {
          verdict: 'HALT',
          controlVariationKey: 'control',
          experimentRevision: 7,
          isCurrent: true,
        },
        aiInsights: null,
        created: '2026-08-28T01:00:00Z',
      }],
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('Guardrail regression caused an automatic pause')
    expect(wrapper.text()).toContain('Latest verdict')
    expect(wrapper.text()).not.toContain('historical definition')
  })
})
