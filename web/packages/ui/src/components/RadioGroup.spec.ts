import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import RadioGroup from './RadioGroup.vue'

const options = [
  { value: 'a', label: 'Alpha' },
  { value: 'b', label: 'Beta' },
  { value: 'c', label: 'Gamma' },
]

function mountRadio(props: Record<string, unknown> = {}) {
  return shallowMount(RadioGroup, {
    props: { modelValue: 'a', options, ...props },
  })
}

describe('RadioGroup', () => {
  it('renders all options', () => {
    const w = mountRadio()
    const labels = w.findAll('.radio-option')
    expect(labels).toHaveLength(3)
    expect(labels[0].find('.radio-text').text()).toBe('Alpha')
    expect(labels[1].find('.radio-text').text()).toBe('Beta')
    expect(labels[2].find('.radio-text').text()).toBe('Gamma')
  })

  it('marks the selected option with the "selected" class', () => {
    const w = mountRadio({ modelValue: 'b' })
    const circles = w.findAll('.radio-circle')
    expect(circles[0].classes()).not.toContain('selected')
    expect(circles[1].classes()).toContain('selected')
    expect(circles[2].classes()).not.toContain('selected')
  })

  it('shows the dot only for the selected option', () => {
    const w = mountRadio({ modelValue: 'b' })
    const dots = w.findAll('.radio-dot')
    expect(dots).toHaveLength(1)
  })

  it('emits update:modelValue when an option is clicked', async () => {
    const w = mountRadio({ modelValue: 'a' })
    const buttons = w.findAll('.radio-circle')
    await buttons[2].trigger('click')
    expect(w.emitted('update:modelValue')![0]).toEqual(['c'])
  })

  it('applies disabled class and attribute', () => {
    const w = mountRadio({ disabled: true })
    expect(w.find('.radio-group').classes()).toContain('disabled')
    const buttons = w.findAll('.radio-circle')
    buttons.forEach(b => expect(b.attributes('disabled')).toBeDefined())
  })

  it('renders label as legend', () => {
    const w = mountRadio({ label: 'Choose' })
    expect(w.find('.radio-group-label').text()).toBe('Choose')
  })

  it('hides legend when label not provided', () => {
    const w = mountRadio()
    expect(w.find('.radio-group-label').exists()).toBe(false)
  })

  it('applies vertical direction class by default', () => {
    const w = mountRadio()
    expect(w.find('.radio-options').classes()).toContain('vertical')
  })

  it('applies horizontal direction class', () => {
    const w = mountRadio({ direction: 'horizontal' })
    expect(w.find('.radio-options').classes()).toContain('horizontal')
  })

  it('applies accent color to selected border', () => {
    const w = mountRadio({ modelValue: 'a', accent: '#ff0000' })
    const selected = w.findAll('.radio-circle').find(c => c.classes().includes('selected'))!
    expect(selected.attributes('style')).toContain('border-color: #ff0000')
  })
})
