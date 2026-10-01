import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import { defineComponent } from 'vue'
import TagInput from './TagInput.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountTagInput(props: Record<string, unknown> = {}) {
  return mount(TagInput, {
    props,
    global: { stubs: { Icon: IconStub } },
  })
}

describe('TagInput', () => {
  it('renders existing tags as chips', () => {
    const w = mountTagInput({ modelValue: ['vue', 'react'] })
    const chips = w.findAll('.tag-chip')
    expect(chips).toHaveLength(2)
    expect(chips[0].text()).toContain('vue')
    expect(chips[1].text()).toContain('react')
  })

  it('Enter key adds tag', async () => {
    const w = mountTagInput({ modelValue: [] })
    const input = w.find('.tag-input-el')
    await input.setValue('new-tag')
    await input.trigger('keydown', { key: 'Enter' })
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([['new-tag']])
  })

  it('duplicate tags prevented', async () => {
    const w = mountTagInput({ modelValue: ['existing'] })
    const input = w.find('.tag-input-el')
    await input.setValue('existing')
    await input.trigger('keydown', { key: 'Enter' })
    // Model should not have changed with a duplicate
    const emitted = w.emitted('update:modelValue')
    // Either no emission or the last emission still has the same single item
    if (emitted) {
      const last = emitted[emitted.length - 1]![0] as string[]
      expect(last.filter(t => t === 'existing')).toHaveLength(1)
    }
  })

  it('Backspace removes last tag when input empty', async () => {
    const w = mountTagInput({ modelValue: ['a', 'b'] })
    const input = w.find('.tag-input-el')
    await input.trigger('keydown', { key: 'Backspace' })
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([['a']])
  })

  it('clicking remove button removes tag', async () => {
    const w = mountTagInput({ modelValue: ['a', 'b'] })
    const removeButtons = w.findAll('.tag-remove')
    await removeButtons[0].trigger('click')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([['b']])
  })

  it('empty input not added', async () => {
    const w = mountTagInput({ modelValue: [] })
    const input = w.find('.tag-input-el')
    await input.setValue('   ')
    await input.trigger('keydown', { key: 'Enter' })
    const emitted = w.emitted('update:modelValue')
    // Empty/whitespace-only values should not be added
    if (emitted) {
      const last = emitted[emitted.length - 1]![0] as string[]
      expect(last).toHaveLength(0)
    }
  })

  it('applies disabled state', () => {
    const w = mountTagInput({ disabled: true })
    expect(w.find('.tag-input-wrap').classes()).toContain('disabled')
    expect((w.find('.tag-input-el').element as HTMLInputElement).disabled).toBe(true)
  })

  it('renders label', () => {
    const w = mountTagInput({ label: 'Tags' })
    expect(w.find('.tag-input-label').text()).toBe('Tags')
  })

  it('hides label when not provided', () => {
    const w = mountTagInput()
    expect(w.find('.tag-input-label').exists()).toBe(false)
  })
})
