import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import FormRow from './FormRow.vue'

describe('FormRow', () => {
  it('renders slot content', () => {
    const w = shallowMount(FormRow, {
      slots: { default: '<div class="field">Field</div>' },
    })
    expect(w.find('.field').exists()).toBe(true)
  })

  it('sets gap style from prop', () => {
    const w = shallowMount(FormRow, { props: { gap: 20 } })
    expect(w.attributes('style')).toContain('gap: 20px')
  })

  it('defaults gap to 14px', () => {
    const w = shallowMount(FormRow)
    expect(w.attributes('style')).toContain('gap: 14px')
  })

  it('has form-row class', () => {
    const w = shallowMount(FormRow)
    expect(w.classes()).toContain('form-row')
  })
})
