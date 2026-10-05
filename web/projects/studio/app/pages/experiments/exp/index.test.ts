import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { defineComponent, nextTick, ref } from 'vue'
import ExperimentList from './index.vue'

const mutation = vi.fn().mockResolvedValue({})
const refresh = vi.fn()

const experimentsData = ref({ experiments: { all: [] } })
function flagData(variationKeys: string[] = ['control', 'treatment']) {
  return {
    featureFlags: {
      all: [{
      id: 'flag-1',
      key: 'checkout',
      name: 'Checkout',
      variations: [
        { key: 'control', name: 'Control', value: false },
        { key: 'treatment', name: 'Treatment', value: true },
      ],
      targetingRules: [{
        id: 'rule-1',
        name: 'Checkout rollout',
        conditions: [],
        rollout: {
          variationWeights: variationKeys.map(variationKey => ({ variationKey, weight: 50 })),
        },
      }],
      }],
    },
  }
}

const flagsData = ref(flagData())

vi.stubGlobal('useRouter', () => ({ push: vi.fn() }))
vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#5ec5ff' }))
vi.stubGlobal('useToast', () => ({ success: vi.fn(), error: vi.fn() }))
vi.stubGlobal('useCreateFromQuery', () => undefined)
vi.stubGlobal('useGraphQL', () => ({
  mutation,
  useAsyncQuery: (key: string) => key === 'experiments-list'
    ? { data: experimentsData, status: ref('success'), refresh }
    : { data: flagsData, status: ref('success'), refresh: vi.fn() },
}))

const passthrough = {
  template: '<div><slot name="header" /><slot name="actions" /><slot /><slot name="footer" /></div>',
}
const ButtonStub = defineComponent({
  name: 'Button',
  props: { disabled: Boolean },
  emits: ['click'],
  template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
})
const SelectStub = defineComponent({
  name: 'Select',
  props: {
    modelValue: { type: String, default: '' },
    label: { type: String, default: '' },
    options: { type: Array, default: () => [] },
  },
  emits: ['update:modelValue'],
  template: '<div />',
})
const TextInputStub = defineComponent({
  name: 'TextInput',
  props: { modelValue: { type: String, default: '' } },
  emits: ['update:modelValue'],
  template: '<input />',
})

function mountPage() {
  return mount(ExperimentList, {
    global: {
      stubs: {
        PageShell: passthrough,
        PageHeader: passthrough,
        SectionCard: passthrough,
        GlassTable: passthrough,
        Pagination: passthrough,
        Modal: passthrough,
        ConfirmModal: passthrough,
        Badge: passthrough,
        Button: ButtonStub,
        Select: SelectStub,
        TextInput: TextInputStub,
        Textarea: { template: '<textarea />' },
      },
      mocks: { buildBreadcrumb: (...values: string[]) => values },
    },
  })
}

enableAutoUnmount(afterEach)

describe('Experiment creation', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    flagsData.value = flagData()
  })

  it('requires a targeting rule and submits its id instead of a default path', async () => {
    const wrapper = mountPage()
    await wrapper.findAll('button').find(button => button.text() === 'New Experiment')!.trigger('click')

    const featureFlagSelect = wrapper.findAllComponents(SelectStub)
      .find(select => select.props('label') === 'Feature Flag')!
    featureFlagSelect.vm.$emit('update:modelValue', 'flag-1')
    await nextTick()

    const attachedRuleSelect = wrapper.findAllComponents(SelectStub)
      .find(select => select.props('label') === 'Attached Rule')!
    expect(attachedRuleSelect.props('options')).toEqual([
      { value: 'rule-1', label: 'Rule 1: Checkout rollout' },
    ])
    expect(wrapper.findAll('button').find(button => button.text() === 'Create')!.attributes('disabled')).toBeDefined()

    attachedRuleSelect.vm.$emit('update:modelValue', 'rule-1')
    await nextTick()
    const controlSelect = wrapper.findAllComponents(SelectStub)
      .find(select => select.props('label') === 'Control variation')!
    controlSelect.vm.$emit('update:modelValue', 'control')
    wrapper.findComponent(TextInputStub).vm.$emit('update:modelValue', 'Checkout experiment')
    await nextTick()

    await wrapper.findAll('button').find(button => button.text() === 'Create')!.trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      experiment: expect.objectContaining({
        name: 'Checkout experiment',
        featureFlagId: 'flag-1',
        targetingRuleId: 'rule-1',
        controlVariationKey: 'control',
      }),
    })
  })

  it('does not offer a targeting rule that serves only one variation', async () => {
    flagsData.value = flagData(['control'])
    const wrapper = mountPage()
    await wrapper.findAll('button').find(button => button.text() === 'New Experiment')!.trigger('click')

    const featureFlagSelect = wrapper.findAllComponents(SelectStub)
      .find(select => select.props('label') === 'Feature Flag')!
    featureFlagSelect.vm.$emit('update:modelValue', 'flag-1')
    await nextTick()

    const attachedRuleSelect = wrapper.findAllComponents(SelectStub)
      .find(select => select.props('label') === 'Attached Rule')!
    expect(attachedRuleSelect.props('options')).toEqual([])
    expect(wrapper.findAll('button').find(button => button.text() === 'Create')!.attributes('disabled')).toBeDefined()
  })
})
