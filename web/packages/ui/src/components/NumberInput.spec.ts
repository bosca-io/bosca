import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import NumberInput from './NumberInput.vue'

function mountInput(props: Record<string, unknown> = {}) {
  return mount(NumberInput, { props })
}

describe('NumberInput', () => {
  it('renders with a numeric value', () => {
    const w = mountInput({ modelValue: 42 })
    expect((w.find('input').element as HTMLInputElement).value).toBe('42')
  })

  it('renders empty when modelValue is null', () => {
    const w = mountInput({ modelValue: null })
    expect((w.find('input').element as HTMLInputElement).value).toBe('')
  })

  it('emits update:modelValue with a number on input', async () => {
    const w = mountInput({ modelValue: null })
    const input = w.find('input')
    // Simulate native input event
    const el = input.element as HTMLInputElement
    el.value = '25'
    await input.trigger('input')
    const emitted = w.emitted('update:modelValue')!
    expect(emitted[emitted.length - 1]).toEqual([25])
  })

  it('emits null when input is cleared', async () => {
    const w = mountInput({ modelValue: 10 })
    const input = w.find('input')
    const el = input.element as HTMLInputElement
    el.value = ''
    await input.trigger('input')
    const emitted = w.emitted('update:modelValue')!
    expect(emitted[emitted.length - 1]).toEqual([null])
  })

  it('renders label when provided', () => {
    const w = mountInput({ label: 'Quantity' })
    expect(w.find('.number-input-label').text()).toBe('Quantity')
  })

  it('hides label when not provided', () => {
    const w = mountInput()
    expect(w.find('.number-input-label').exists()).toBe(false)
  })

  it('applies disabled state', () => {
    const w = mountInput({ disabled: true })
    expect(w.find('.number-input-wrap').classes()).toContain('disabled')
    expect((w.find('input').element as HTMLInputElement).disabled).toBe(true)
  })

  it('emits blur event', async () => {
    const w = mountInput()
    await w.find('input').trigger('blur')
    expect(w.emitted('blur')).toHaveLength(1)
  })

  it('sets min, max, step attributes', () => {
    const w = mountInput({ min: 0, max: 100, step: 5 })
    const input = w.find('input')
    expect(input.attributes('min')).toBe('0')
    expect(input.attributes('max')).toBe('100')
    expect(input.attributes('step')).toBe('5')
  })
})
