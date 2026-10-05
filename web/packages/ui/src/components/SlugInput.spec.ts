import { describe, it, expect, vi } from 'vitest'
import { mount } from '../test-helpers'
import { defineComponent, nextTick } from 'vue'
import SlugInput from './SlugInput.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountSlug(props: Record<string, unknown> = {}) {
  return mount(SlugInput, {
    props,
    global: { stubs: { Icon: IconStub } },
  })
}

/** Simulate a native input event on an input element (the component listens to @input) */
function simulateNativeInput(w: ReturnType<typeof mount>, value: string) {
  const input = w.find('.slug-input-el').element as HTMLInputElement
  input.value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('SlugInput', () => {
  it('auto-generates slug from source', async () => {
    const w = mountSlug({ source: '', modelValue: '' })
    await w.setProps({ source: 'Hello World' })
    await nextTick()
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]![0] as string
    expect(last).toBe('hello-world')
  })

  it('formats slug correctly', async () => {
    const w = mountSlug({ source: '', modelValue: '' })
    await w.setProps({ source: 'My Cool Title!!' })
    await nextTick()
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]![0] as string
    expect(last).toBe('my-cool-title')
  })

  it('manual edit stops auto-generation', async () => {
    const w = mountSlug({ source: '', modelValue: '' })
    await w.setProps({ source: 'Original' })
    await nextTick()
    // Simulate manual edit via native input
    simulateNativeInput(w, 'custom-slug')
    await nextTick()
    // Now change source -- should NOT auto-generate
    const countBefore = (w.emitted('update:modelValue') ?? []).length
    await w.setProps({ source: 'New Source' })
    await nextTick()
    const countAfter = (w.emitted('update:modelValue') ?? []).length
    expect(countAfter).toBe(countBefore)
  })

  it('reset button appears after manual edit', async () => {
    const w = mountSlug({ source: 'Original', modelValue: 'original' })
    expect(w.find('.slug-reset').exists()).toBe(false)
    simulateNativeInput(w, 'custom')
    await nextTick()
    expect(w.find('.slug-reset').exists()).toBe(true)
  })

  it('reset button restores auto-generation', async () => {
    const w = mountSlug({ source: 'My Title', modelValue: 'my-title' })
    simulateNativeInput(w, 'custom')
    await nextTick()
    await w.find('.slug-reset').trigger('click')
    await nextTick()
    const emitted = w.emitted('update:modelValue')!
    const last = emitted[emitted.length - 1]![0] as string
    expect(last).toBe('my-title')
  })

  it('validation check runs after debounce', async () => {
    vi.useFakeTimers()
    const onValidate = vi.fn().mockResolvedValue(true)
    const w = mountSlug({ source: '', modelValue: '', onValidate, debounce: 100 })
    await w.setProps({ source: 'test slug' })
    await nextTick()
    expect(onValidate).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(150)
    expect(onValidate).toHaveBeenCalledWith('test-slug')
    vi.useRealTimers()
  })

  it('shows available status after validation passes', async () => {
    vi.useFakeTimers()
    const onValidate = vi.fn().mockResolvedValue(true)
    const w = mountSlug({ source: '', modelValue: '', onValidate, debounce: 50, label: 'Slug' })
    await w.setProps({ source: 'test' })
    await nextTick()
    expect(w.find('.slug-checking').exists()).toBe(true)
    await vi.advanceTimersByTimeAsync(100)
    await nextTick()
    expect(w.find('.slug-available').exists()).toBe(true)
    vi.useRealTimers()
  })

  it('shows taken status after validation fails', async () => {
    vi.useFakeTimers()
    const onValidate = vi.fn().mockResolvedValue(false)
    const w = mountSlug({ source: '', modelValue: '', onValidate, debounce: 50, label: 'Slug' })
    await w.setProps({ source: 'taken' })
    await nextTick()
    await vi.advanceTimersByTimeAsync(100)
    await nextTick()
    expect(w.find('.slug-taken').exists()).toBe(true)
    vi.useRealTimers()
  })
})
