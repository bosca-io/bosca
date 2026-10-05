import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import DateInput from './DateInput.vue'

function mountDate(props: Record<string, unknown> = {}) {
  return mount(DateInput, { props })
}

describe('DateInput', () => {
  it('renders input with default type datetime-local', () => {
    const w = mountDate()
    expect(w.find('.date-input-el').attributes('type')).toBe('datetime-local')
  })

  it('renders input with specified type', () => {
    const w = mountDate({ type: 'date' })
    expect(w.find('.date-input-el').attributes('type')).toBe('date')
  })

  it('binds v-model value', () => {
    const w = mountDate({ modelValue: '2025-01-15T10:30' })
    expect((w.find('.date-input-el').element as HTMLInputElement).value).toBe('2025-01-15T10:30')
  })

  it('emits update:modelValue on input change', async () => {
    let current = '2025-01-01'
    const w = mount(DateInput, {
      props: {
        modelValue: current,
        'onUpdate:modelValue': (v: string) => { current = v },
      },
    })
    const inputEl = w.find('.date-input-el')
    // v-model on native input: setValue triggers the input event that Vue handles
    await inputEl.setValue('2025-06-15')
    const emitted = w.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
  })

  it('renders label when provided', () => {
    const w = mountDate({ label: 'Start Date' })
    expect(w.find('.date-input-label').text()).toBe('Start Date')
  })

  it('hides label when not provided', () => {
    const w = mountDate()
    expect(w.find('.date-input-label').exists()).toBe(false)
  })

  it('applies disabled state', () => {
    const w = mountDate({ disabled: true })
    expect(w.find('.date-input-wrap').classes()).toContain('disabled')
    expect((w.find('.date-input-el').element as HTMLInputElement).disabled).toBe(true)
  })

  it('emits blur event', async () => {
    const w = mountDate()
    await w.find('.date-input-el').trigger('blur')
    expect(w.emitted('blur')).toHaveLength(1)
  })
})
