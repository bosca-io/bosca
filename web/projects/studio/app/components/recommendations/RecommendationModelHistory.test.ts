import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import GlassTable from '../../../../../packages/ui/src/components/GlassTable.vue'
import SectionCard from '../../../../../packages/ui/src/components/SectionCard.vue'
import RecommendationModelHistory from './RecommendationModelHistory.vue'

const mutation = vi.fn()
const refresh = vi.fn()
const model = (version: number, status: string, exported = false) => ({
  version, revision: 12, status, exported, personalized: true, pinned: false, failure: null,
  created: '2026-09-11T16:00:00Z', context: { name: 'Default' },
})
const data = ref({ recommendation: { contexts: {
  context: { activeModelVersion: 1, requestedModelVersion: 13, revision: 12 },
  models: [model(16, 'RUNNING', true), model(15, 'FAILED'), model(13, 'COMPLETED', true), model(12, 'COMPLETED', true), model(1, 'COMPLETED', true)],
} } })
let wrapper: ReturnType<typeof mount>

beforeEach(() => {
  vi.useFakeTimers()
  mutation.mockReset().mockResolvedValue({})
  refresh.mockReset().mockResolvedValue(undefined)
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#6366f1' }))
  vi.stubGlobal('useGraphQL', () => ({ mutation, useAsyncQuery: () => ({ data, error: ref(null), status: ref('success'), refresh }) }))
  wrapper = mount(RecommendationModelHistory, { props: { contextId: 'context-id' }, global: {
    components: { GlassTable, SectionCard },
    stubs: {
      Button: { template: '<button><slot /></button>' },
      Badge: { template: '<span><slot /></span>' },
      RecommendationModelDetails: { props: ['version'], template: '<div data-test="details">Model {{ version }} details</div>' },
      ConfirmModal: { props: ['title', 'loading'], emits: ['confirm', 'close'], template: '<div data-test="confirmation">{{ title }}<slot /><button :disabled="loading" @click="$emit(\'confirm\')">Confirm deletion</button><button @click="$emit(\'close\')">Cancel</button></div>' },
    },
  } })
})
afterEach(() => { wrapper.unmount(); vi.useRealTimers() })

async function menu(version: number) {
  const row = wrapper.findAll('.gt-row').find(row => row.text().startsWith(`v${version}`))!
  await row.get('.gt-action-btn').trigger('click')
  return row
}

describe('RecommendationModelHistory', () => {
  it('uses the trained versions table and distinguishes exported training from serving', async () => {
    expect(wrapper.text()).toContain('Trained versions')
    expect(wrapper.text()).toContain('Trained · waiting for serving')
    expect(wrapper.text()).toContain('Active model: 1.')
    expect(wrapper.text()).toContain('Waiting for model 13 to load.')
    expect(wrapper.text()).not.toContain('Delete model')
    await wrapper.get('button[aria-label="Refresh model status"]').trigger('click')
    expect(refresh).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(10000)
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('protects active, requested, and running versions from deletion', async () => {
    for (const version of [1, 13, 16]) {
      const row = await menu(version)
      const action = row.findAll('.overflow-item').find(button => button.text() === 'Delete model')!
      expect(action.attributes('disabled')).toBeDefined()
      await row.get('.gt-action-btn').trigger('click')
    }
    expect(mutation).not.toHaveBeenCalled()
  })

  it('deletes an inactive model only after confirmation and refreshes its history', async () => {
    const row = await menu(12)
    await row.findAll('.overflow-item').find(button => button.text() === 'Delete model')!.trigger('click')
    expect(mutation).not.toHaveBeenCalled()
    expect(wrapper.get('[data-test="confirmation"]').text()).toContain('Delete model 12')
    await wrapper.get('[data-test="confirmation"] button').trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledWith(expect.anything(), { id: 'context-id', version: 12 })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-test="confirmation"]').exists()).toBe(false)
  })

  it('keeps deletion failure visible for retry without hiding the model', async () => {
    mutation.mockRejectedValueOnce(new Error('Model is now active'))
    const row = await menu(15)
    await row.findAll('.overflow-item').find(button => button.text() === 'Delete model')!.trigger('click')
    await wrapper.get('[data-test="confirmation"] button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-test="confirmation"]').text()).toContain('Model is now active')
    expect(refresh).not.toHaveBeenCalled()
  })

  it('opens captured configuration from the compact row menu', async () => {
    const row = await menu(12)
    await row.findAll('.overflow-item').find(button => button.text() === 'Model details')!.trigger('click')
    expect(wrapper.get('[data-test="details"]').text()).toContain('Model 12 details')
  })
})
