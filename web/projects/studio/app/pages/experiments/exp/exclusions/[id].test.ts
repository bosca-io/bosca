import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { ref } from 'vue'
import ExperimentExclusions from './[id].vue'

const mutation = vi.fn().mockResolvedValue({})
const query = vi.fn()
const refreshExperiment = vi.fn()
const refreshAccounts = vi.fn()
const toastSuccess = vi.fn()
const toastError = vi.fn()

const experimentData = ref({
  experiments: {
    experiment: {
      id: 'exp-1',
      name: 'Checkout test',
      description: 'Compare checkout variants',
      hypothesis: 'The treatment converts better',
      status: 'RUNNING',
      startDate: '2026-09-01T00:00:00Z',
      endDate: null,
      targetSampleSize: 5000,
      featureFlagId: 'flag-1',
      targetingRuleId: 'rule-1',
      controlVariationKey: 'control',
      excludedPrincipalIds: ['principal-1'],
      exclusionLayer: { id: 'layer-1' },
      analysisMethod: 'BAYESIAN',
      bayesianPrior: {
        betaPriorAlpha: 1,
        betaPriorBeta: 1,
        normalPriorMean: null,
        normalPriorVariance: null,
      },
      rolloutPolicy: {
        mode: 'ADAPTIVE_STEPS',
        treatmentVariationKey: 'treatment',
        steps: [{ weightPercent: 25, afterDuration: 'PT1H' }],
        incrementPercent: null,
        minConfidence: 0.95,
        guardrailThreshold: null,
        guardrailMinRegressionPercent: null,
        haltOnGuardrail: true,
      },
    },
  },
})

const searchedProfile = {
  id: 'profile-2',
  name: 'Alex Smith',
  attributes: [{ typeId: 'bosca.profiles.email', attributes: { email: 'alex@example.com' } }],
  principal: {
    id: 'principal-2',
    verified: true,
    primaryProfileId: 'profile-2',
    credentials: [
      { type: 'PASSWORD', identifier: 'alex@example.com' },
      { type: 'OAUTH2', identifier: 'alex-google' },
    ],
  },
}

const excludedAccount = {
  id: 'principal-1',
  verified: true,
  primaryProfileId: 'profile-1',
  credentials: [],
  profiles: [{
    id: 'profile-1',
    name: 'Alex Smith',
    attributes: [{ typeId: 'bosca.profiles.email', attributes: { email: 'alex.personal@example.com' } }],
  }],
}

const accountsData = ref({
  search: { search: { documents: [{ profile: searchedProfile }], estimatedHits: 1 } },
})

vi.stubGlobal('useRoute', () => ({ params: { id: 'exp-1' } }))
vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useToast', () => ({ success: toastSuccess, error: toastError }))
vi.stubGlobal('useGraphQL', () => ({
  mutation,
  query,
  useAsyncQuery: (key: string) => key === 'experiment-account-exclusions'
    ? {
        data: experimentData,
        status: ref('success'),
        error: ref(null),
        refresh: refreshExperiment,
      }
    : {
        data: accountsData,
        status: ref('success'),
        error: ref(null),
        refresh: refreshAccounts,
      },
}))

const passthrough = {
  template: '<div><slot name="header" /><slot name="actions" /><slot name="right" /><slot /></div>',
}

const stubs = {
  PageShell: passthrough,
  PageHeader: passthrough,
  SectionCard: passthrough,
  Button: {
    props: ['disabled'],
    template: '<button :disabled="disabled"><slot /></button>',
  },
  Avatar: { props: ['name'], template: '<span>{{ name }}</span>' },
  Badge: { template: '<span><slot /></span>' },
  SearchInput: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<input :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
  },
  Checkbox: {
    props: ['modelValue'],
    emits: ['update:modelValue'],
    template: '<button class="select-toggle" @click="$emit(\'update:modelValue\', !modelValue)">{{ modelValue }}</button>',
  },
  Pagination: passthrough,
  GlassTable: {
    props: ['rows'],
    template: `
      <div>
        <div v-for="row in rows" :key="row.id" class="table-row">
          <slot name="col-selected" :row="row" />
          <slot name="col-account" :row="row" />
          <slot name="col-email" :row="row" />
          <slot name="col-identifier" :row="row" />
          <slot name="col-status" :row="row" />
          <slot name="actions" :row="row" />
        </div>
      </div>
    `,
  },
}

function mountPage() {
  return mount(ExperimentExclusions, {
    global: {
      stubs,
      mocks: { buildBreadcrumb: (...values: string[]) => values },
    },
  })
}

enableAutoUnmount(afterEach)

describe('Experiment account exclusions', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    experimentData.value.experiments.experiment.excludedPrincipalIds = ['principal-1']
    accountsData.value = {
      search: { search: { documents: [{ profile: searchedProfile }], estimatedHits: 1 } },
    }
    query.mockResolvedValue({
      security: { principals: { principal: excludedAccount } },
    })
  })

  it('shows account emails and saves the selected principal IDs without dropping experiment settings', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('alex.personal@example.com')
    expect(wrapper.text()).toContain('alex@example.com')
    expect(wrapper.text()).toContain('alex-google')

    await wrapper.find('.select-toggle').trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(button => button.text() === 'Save exclusions')!.trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      id: 'exp-1',
      experiment: expect.objectContaining({
        featureFlagId: 'flag-1',
        targetingRuleId: 'rule-1',
        controlVariationKey: 'control',
        excludedPrincipalIds: ['principal-1', 'principal-2'],
        exclusionLayerId: 'layer-1',
        analysisMethod: 'BAYESIAN',
        rolloutPolicy: experimentData.value.experiments.experiment.rolloutPolicy,
      }),
    })
    expect(toastSuccess).toHaveBeenCalledWith('Excluded accounts updated')
    expect(refreshExperiment).toHaveBeenCalled()
  })
})
