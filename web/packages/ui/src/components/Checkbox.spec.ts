import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Checkbox from './Checkbox.vue'

describe('Checkbox', () => {
  it('renders checked SVG when modelValue is true', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: true } })
    const svg = w.find('svg')
    expect(svg.exists()).toBe(true)
    // Check SVG path includes the checkmark path
    expect(svg.find('path').attributes('d')).toContain('2.5 6')
  })

  it('renders no SVG when unchecked and not indeterminate', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false } })
    expect(w.find('svg').exists()).toBe(false)
  })

  it('renders dash SVG when indeterminate and not checked', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false, indeterminate: true } })
    const svg = w.find('svg')
    expect(svg.exists()).toBe(true)
    expect(svg.find('path').attributes('d')).toBe('M3 6h6')
  })

  it('emits update:modelValue toggling the value on click', async () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false } })
    await w.find('button').trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual([true])
  })

  it('emits false when currently true', async () => {
    const w = shallowMount(Checkbox, { props: { modelValue: true } })
    await w.find('button').trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual([false])
  })

  it('shows label text when label prop is set', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false, label: 'Accept' } })
    expect(w.find('.checkbox-label').text()).toBe('Accept')
  })

  it('hides label when label prop is not set', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false } })
    expect(w.find('.checkbox-label').exists()).toBe(false)
  })

  it('applies disabled class and attribute', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: false, disabled: true } })
    expect(w.find('.checkbox-row').classes()).toContain('disabled')
    expect(w.find('button').attributes('disabled')).toBeDefined()
  })

  it('applies accent color when checked', () => {
    const w = shallowMount(Checkbox, { props: { modelValue: true, accent: '#ff0000' } })
    const style = w.find('.checkbox-box').attributes('style')
    expect(style).toContain('background: #ff0000')
    expect(style).toContain('border-color: #ff0000')
  })
})
