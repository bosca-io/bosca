import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { print } from 'graphql'
import ContextsPage from './index.vue'

const mutation = vi.fn()
const success = vi.fn()
const errorToast = vi.fn()
let wrapper: ReturnType<typeof mount>

beforeEach(() => {
  mutation.mockReset().mockResolvedValue({ recommendation: { contexts: { recompute: true } } })
  success.mockClear()
  errorToast.mockClear()
  vi.stubGlobal('useGraphQL', () => ({
    mutation,
    useAsyncQuery: () => ({ data: ref({ recommendation: { contexts: { all: [] } } }), status: ref('success'), error: ref(null) }),
  }))
  vi.stubGlobal('useCurrentSubsystem', () => ({ accent: '#6366f1' }))
  vi.stubGlobal('useToast', () => ({ success, error: errorToast }))
  vi.stubGlobal('buildBreadcrumb', () => [])
  wrapper = mount(ContextsPage, { global: { mocks: { buildBreadcrumb: () => [] }, stubs: {
    PageShell: { template: '<main><slot name="header" /><slot /></main>' },
    PageHeader: { template: '<header><slot name="actions" /></header>' },
    Button: { props: ['disabled'], emits: ['click'], template: '<button :disabled="disabled" @click="$emit(\'click\')"><slot /></button>' },
    GlassTable: true,
  } } })
})
afterEach(() => wrapper.unmount())

function recomputeButton() {
  return wrapper.get('button')
}

describe('Recommendation context recomputation', () => {
  it('queues recomputation only when requested, including after the last context is removed', async () => {
    expect(mutation).not.toHaveBeenCalled()
    expect(recomputeButton().text()).toBe('Recompute assignments')
    expect(wrapper.text()).toContain('all contexts')
    await recomputeButton().trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(1)
    expect(print(mutation.mock.calls[0]![0])).toContain('contexts {\n      recompute\n    }')
    expect(print(mutation.mock.calls[0]![0])).not.toContain('trainModel')
    expect(success).toHaveBeenCalledWith('Content assignment recomputation queued for all contexts.')
  })

  it('prevents duplicate requests and allows retry after an error', async () => {
    let rejectRecompute: (error: Error) => void = () => {}
    mutation.mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectRecompute = reject }))
    await recomputeButton().trigger('click')
    expect(recomputeButton().attributes('disabled')).toBeDefined()
    await recomputeButton().trigger('click')
    expect(mutation).toHaveBeenCalledTimes(1)
    rejectRecompute(new Error('Recomputation is unavailable'))
    await flushPromises()
    expect(errorToast).toHaveBeenCalledWith('Recomputation is unavailable')
    expect(recomputeButton().attributes('disabled')).toBeUndefined()
    await recomputeButton().trigger('click')
    await flushPromises()
    expect(mutation).toHaveBeenCalledTimes(2)
  })
})
