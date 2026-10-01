import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { print } from 'graphql'
import * as contextForm from '~/utils/recommendationContextForm'
import ContextPage from './[id].vue'
import NewContextPage from './new.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const refreshHistory = vi.fn()
const success = vi.fn()
const errorToast = vi.fn()
const push = vi.fn()
const form = contextForm.createRecommendationContextForm()
const savedContext = {
  id: 'reading-id', type: 'reading', name: 'Reading', description: '', revision: 3,
  created: '2026-09-01T12:00:00Z', modified: '2026-09-01T12:00:00Z',
  weights: form.weights, contentFilter: { metadata: form.metadata, collections: form.collections },
}
const data = ref<{ recommendation: { contexts: { context: typeof savedContext | null } } }>({
  recommendation: { contexts: { context: savedContext } },
})
let wrapper: ReturnType<typeof mount>

const stubs = {
  PageShell: { template: '<main><slot name="header" /><slot /></main>' },
  PageHeader: { props: ['title'], template: '<header>{{ title }}<slot name="actions" /></header>' },
  SectionCard: { template: '<section><slot /></section>' },
  Button: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
  Tabs: { props: ['tabs', 'modelValue'], emits: ['update:modelValue'], template: '<nav><button v-for="tab in tabs" :key="tab" @click="$emit(\'update:modelValue\', tab)">{{ tab }}</button></nav>' },
  RecommendationContextEditor: {
    props: ['modelValue', 'saving', 'error', 'submitLabel'], emits: ['submit'],
    template: '<div><input aria-label="Name" v-model="modelValue.name"><input aria-label="Type" v-model="modelValue.type"><p>{{ error }}</p><button :disabled="saving" @click="$emit(\'submit\')">{{ submitLabel || "Save changes" }}</button></div>',
  },
  RecommendationModelHistory: { props: ['contextId'], setup() { return { refresh: refreshHistory } }, template: '<section data-test="history">Models for {{ contextId }}</section>' },
  ConfirmModal: true,
}

function button(label: string) {
  const result = wrapper.findAll('button').find(candidate => candidate.text() === label)
  if (!result) throw new Error(`Button not found: ${label}`)
  return result
}

beforeEach(() => {
  vi.clearAllMocks()
  mutation.mockReset().mockResolvedValue({ recommendation: { contexts: { trainModel: { version: 12 }, add: { id: 'created-id' } } } })
  refresh.mockReset().mockResolvedValue(undefined)
  refreshHistory.mockReset().mockResolvedValue(undefined)
  data.value = { recommendation: { contexts: { context: structuredClone(savedContext) } } }
  for (const [name, value] of Object.entries(contextForm)) vi.stubGlobal(name, value)
  vi.stubGlobal('useGraphQL', () => ({ mutation, useAsyncQuery: () => ({ data, status: ref('success'), error: ref(null), refresh }) }))
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#6366f1' }))
  vi.stubGlobal('useRoute', () => ({ params: { id: 'reading-id' } }))
  vi.stubGlobal('useRouter', () => ({ push }))
  vi.stubGlobal('useToast', () => ({ success, error: errorToast }))
  vi.stubGlobal('buildBreadcrumb', () => [])
  wrapper = mount(ContextPage, { global: { stubs, mocks: { buildBreadcrumb: () => [] } } })
})

afterEach(() => wrapper.unmount())

describe('Recommendation context assignment recomputation', () => {
  it('offers recomputation beside eligibility editing and queues assignments without saving or training', async () => {
    expect(mutation).not.toHaveBeenCalled()
    await button('Eligibility').trigger('click')
    expect(wrapper.text()).toContain('update metadata and collection assignments across all contexts')
    expect(button('Recompute assignments').attributes('title')).toContain('across all contexts')
    await button('Recompute assignments').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(print(mutation.mock.calls[0]![0])).toContain('contexts {\n      recompute\n    }')
    expect(mutation.mock.calls[0]).toHaveLength(1)
    expect(success).toHaveBeenCalledWith('Content assignment recomputation queued for all contexts.')
    expect(refresh).not.toHaveBeenCalled()
    expect(wrapper.find('[data-test="history"]').exists()).toBe(false)
  })

  it('requires saving edits before recomputing assignments', async () => {
    await wrapper.get('input[aria-label="Name"]').setValue('Edited reading')
    expect(button('Recompute assignments').attributes('disabled')).toBeDefined()
    expect(button('Recompute assignments').attributes('title')).toBe('Save your changes before recomputing assignments.')
    await button('Recompute assignments').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
    refresh.mockImplementation(async () => {
      data.value = { recommendation: { contexts: { context: { ...savedContext, name: 'Edited reading', revision: 4 } } } }
    })
    await button('Save changes').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(button('Recompute assignments').attributes('disabled')).toBeUndefined()
    await button('Recompute assignments').trigger('click')
    await flushPromises()
    expect(print(mutation.mock.calls[1]![0])).toContain('mutation RecomputeRecommendationContextAssignments')
  })

  it.each([new Error('Recomputation is unavailable'), 'unavailable'])(
    'prevents duplicate requests and allows retry after failure: %s', async failure => {
      let rejectRecompute: (reason: unknown) => void = () => {}
      mutation.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRecompute = reject }))
      await button('Recompute assignments').trigger('click')
      expect(button('Recompute assignments').attributes('disabled')).toBeDefined()
      expect(button('Train model').attributes('disabled')).toBeDefined()
      expect(button('Delete').attributes('disabled')).toBeDefined()
      await button('Recompute assignments').trigger('click')
      expect(mutation).toHaveBeenCalledTimes(1)
      rejectRecompute(failure)
      await flushPromises()
      expect(errorToast).toHaveBeenCalledWith(failure instanceof Error ? failure.message : 'Failed to recompute content assignments')
      expect(success).not.toHaveBeenCalled()
      expect(button('Recompute assignments').attributes('disabled')).toBeUndefined()
      await button('Recompute assignments').trigger('click')
      await flushPromises()
      expect(mutation).toHaveBeenCalledTimes(2)
    },
  )

  it('does not allow recomputation when the context cannot be loaded', async () => {
    data.value = { recommendation: { contexts: { context: null } } }
    await flushPromises()
    expect(button('Recompute assignments').attributes('disabled')).toBeDefined()
    await button('Recompute assignments').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
  })
})

describe('Recommendation context training', () => {
  it('saves edits without training and requires saving before the training action', async () => {
    expect(button('Train model').attributes('disabled')).toBeUndefined()
    await wrapper.get('input[aria-label="Name"]').setValue('Edited reading')
    expect(button('Train model').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('Save your changes before recomputing assignments or training a model.')
    await button('Train model').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
    refresh.mockImplementation(async () => {
      data.value = { recommendation: { contexts: { context: { ...savedContext, name: 'Edited reading', revision: 4 } } } }
    })
    await button('Save changes').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(print(mutation.mock.calls[0]![0])).toContain('mutation EditRecommendationContext')
    expect(mutation.mock.calls[0]![1]).toMatchObject({ id: 'reading-id', context: { name: 'Edited reading' } })
    expect(success).toHaveBeenCalledWith('Context saved.')
    expect(button('Train model').attributes('disabled')).toBeUndefined()
    expect(wrapper.find('[data-test="history"]').exists()).toBe(false)
  })

  it('trains only the saved context and opens its model history', async () => {
    await button('Train model').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(print(mutation.mock.calls[0]![0])).toContain('trainModel(contextId: $contextId)')
    expect(mutation.mock.calls[0]![1]).toEqual({ contextId: 'reading-id' })
    expect(success).toHaveBeenCalledWith('Model training queued for this context.')
    expect(wrapper.get('[data-test="history"]').text()).toContain('reading-id')
    expect(refresh).not.toHaveBeenCalled()
  })

  it('refreshes already visible history after queuing training', async () => {
    await button('Models').trigger('click')
    await button('Train model').trigger('click')
    await flushPromises()
    expect(refreshHistory).toHaveBeenCalledTimes(1)
  })

  it('blocks duplicate requests while pending and lets the user retry a failed training request', async () => {
    let rejectTraining: (error: Error) => void = () => {}
    mutation.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectTraining = reject }))
    await button('Train model').trigger('click')
    expect(button('Train model').attributes('disabled')).toBeDefined()
    await button('Train model').trigger('click')
    expect(mutation).toHaveBeenCalledTimes(1)
    rejectTraining(new Error('Training is unavailable'))
    await flushPromises()
    expect(errorToast).toHaveBeenCalledWith('Training is unavailable')
    expect(wrapper.find('[data-test="history"]').exists()).toBe(false)
    expect(button('Train model').attributes('disabled')).toBeUndefined()
    await button('Train model').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(2)
  })

  it('does not offer training when the context cannot be loaded', async () => {
    data.value = { recommendation: { contexts: { context: null } } }
    await flushPromises()
    expect(button('Train model').attributes('disabled')).toBeDefined()
    await button('Train model').trigger('click')
    expect(mutation).not.toHaveBeenCalled()
  })

  it('creates a context without sending a training request', async () => {
    wrapper.unmount()
    wrapper = mount(NewContextPage, { global: { stubs, mocks: { buildBreadcrumb: () => [] } } })
    await wrapper.get('input[aria-label="Name"]').setValue('New context')
    await wrapper.get('input[aria-label="Type"]').setValue('new_context')
    await button('Create context').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(print(mutation.mock.calls[0]![0])).toContain('mutation AddRecommendationContext')
    expect(success).toHaveBeenCalledWith('Context created. Use Train model when you are ready to train.')
    expect(push).toHaveBeenCalledWith('/recommendations/contexts/created-id')
  })
})
