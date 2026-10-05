import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import FormField from './FormField.vue'

describe('FormField', () => {
  it('renders label text', () => {
    const w = shallowMount(FormField, { props: { label: 'Name' } })
    expect(w.find('.form-field-label').text()).toContain('Name')
  })

  it('hides label when not provided', () => {
    const w = shallowMount(FormField)
    expect(w.find('.form-field-label').exists()).toBe(false)
  })

  it('shows required indicator when required is true', () => {
    const w = shallowMount(FormField, { props: { label: 'Email', required: true } })
    expect(w.find('.form-field-required').exists()).toBe(true)
    expect(w.find('.form-field-required').text()).toBe('*')
  })

  it('hides required indicator when required is false', () => {
    const w = shallowMount(FormField, { props: { label: 'Email' } })
    expect(w.find('.form-field-required').exists()).toBe(false)
  })

  it('shows error message', () => {
    const w = shallowMount(FormField, { props: { error: 'Required field' } })
    expect(w.find('.form-field-error').text()).toBe('Required field')
  })

  it('shows help text', () => {
    const w = shallowMount(FormField, { props: { help: 'Enter your name' } })
    expect(w.find('.form-field-help').text()).toBe('Enter your name')
  })

  it('hides help text when error is present', () => {
    const w = shallowMount(FormField, {
      props: { help: 'Enter your name', error: 'Required' },
    })
    expect(w.find('.form-field-help').exists()).toBe(false)
    expect(w.find('.form-field-error').exists()).toBe(true)
  })

  it('sets gridColumn from col prop', () => {
    const w = shallowMount(FormField, { props: { col: 6 } })
    expect(w.attributes('style')).toContain('grid-column: span 6')
  })

  it('defaults col to 12', () => {
    const w = shallowMount(FormField)
    expect(w.attributes('style')).toContain('grid-column: span 12')
  })

  it('renders slot content', () => {
    const w = shallowMount(FormField, {
      slots: { default: '<input type="text" />' },
    })
    expect(w.find('input').exists()).toBe(true)
  })
})
