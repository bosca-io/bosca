import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Switch from './Switch.vue'

describe('Switch', () => {
  it('applies the "on" class when modelValue is true', () => {
    const w = shallowMount(Switch, { props: { modelValue: true } })
    expect(w.find('.switch').classes()).toContain('on')
  })

  it('does not have the "on" class when modelValue is false', () => {
    const w = shallowMount(Switch, { props: { modelValue: false } })
    expect(w.find('.switch').classes()).not.toContain('on')
  })

  it('emits update:modelValue with toggled value on click', async () => {
    const w = shallowMount(Switch, { props: { modelValue: false } })
    await w.find('button').trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual([true])
  })

  it('emits false when currently true', async () => {
    const w = shallowMount(Switch, { props: { modelValue: true } })
    await w.find('button').trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual([false])
  })

  it('shows label text', () => {
    const w = shallowMount(Switch, { props: { modelValue: false, label: 'Dark mode' } })
    expect(w.find('.switch-label').text()).toBe('Dark mode')
  })

  it('hides label when not provided', () => {
    const w = shallowMount(Switch, { props: { modelValue: false } })
    expect(w.find('.switch-label').exists()).toBe(false)
  })

  it('sets accent as background color when on', () => {
    const w = shallowMount(Switch, { props: { modelValue: true, accent: '#ff0000' } })
    expect(w.find('.switch').attributes('style')).toContain('background: #ff0000')
  })

  it('does not set accent background when off', () => {
    const w = shallowMount(Switch, { props: { modelValue: false, accent: '#ff0000' } })
    const style = w.find('.switch').attributes('style') || ''
    expect(style).not.toContain('background')
  })
})
