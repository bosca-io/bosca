import { describe, it, expect, vi, beforeEach } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent, nextTick } from 'vue'
import ToastContainer from './ToastContainer.vue'
import { useToast } from '../composables/useToast'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountContainer() {
  return shallowMount(ToastContainer, {
    global: {
      stubs: { Icon: IconStub, Teleport: true, TransitionGroup: false },
    },
  })
}

describe('ToastContainer', () => {
  beforeEach(() => {
    // Clear all toasts before each test
    const { toasts } = useToast()
    toasts.value = []
  })

  it('renders toasts from useToast', async () => {
    const { toasts } = useToast()
    toasts.value = [
      { id: 1, message: 'Success!', tone: 'ok', persistent: false },
      { id: 2, message: 'Error!', tone: 'err', persistent: false },
    ]
    const w = mountContainer()
    await nextTick()
    const msgs = w.findAll('.toast-message')
    expect(msgs).toHaveLength(2)
    expect(msgs[0].text()).toBe('Success!')
    expect(msgs[1].text()).toBe('Error!')
  })

  it('shows progress bar when progress is defined', async () => {
    const { toasts } = useToast()
    toasts.value = [
      { id: 1, message: 'Uploading', tone: 'info', persistent: true, progress: 50 },
    ]
    const w = mountContainer()
    await nextTick()
    const bar = w.find('.toast-progress-bar')
    expect(bar.exists()).toBe(true)
    expect(bar.attributes('style')).toContain('width: 50%')
  })

  it('hides progress bar when progress is undefined', async () => {
    const { toasts } = useToast()
    toasts.value = [
      { id: 1, message: 'Done', tone: 'ok', persistent: false },
    ]
    const w = mountContainer()
    await nextTick()
    expect(w.find('.toast-progress-bar').exists()).toBe(false)
  })

  it('dismiss button removes toast', async () => {
    const { toasts, dismiss } = useToast()
    toasts.value = [
      { id: 99, message: 'Will be removed', tone: 'info', persistent: false },
    ]
    const w = mountContainer()
    await nextTick()
    await w.find('.toast-close').trigger('click')
    await nextTick()
    // After dismiss, the toast should be gone
    expect(toasts.value.find(t => t.id === 99)).toBeUndefined()
  })

  it('renders correct icon for each tone', async () => {
    const { toasts } = useToast()
    toasts.value = [
      { id: 1, message: 'Info', tone: 'info', persistent: false },
    ]
    const w = mountContainer()
    await nextTick()
    const icons = w.findAllComponents(IconStub)
    // First icon in the toast should be the tone icon ('info')
    const toneIcon = icons.find(i => i.props('name') === 'info')
    expect(toneIcon).toBeTruthy()
  })
})
