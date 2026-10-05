import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import FormSection from './FormSection.vue'

describe('FormSection', () => {
  it('renders label as a legend element', () => {
    const w = shallowMount(FormSection, { props: { label: 'Personal Info' } })
    expect(w.find('legend').text()).toBe('Personal Info')
  })

  it('hides legend when label is not provided', () => {
    const w = shallowMount(FormSection)
    expect(w.find('legend').exists()).toBe(false)
  })

  it('renders description paragraph', () => {
    const w = shallowMount(FormSection, {
      props: { description: 'Fill in your details' },
    })
    expect(w.find('.form-section-desc').text()).toBe('Fill in your details')
  })

  it('hides description when not provided', () => {
    const w = shallowMount(FormSection)
    expect(w.find('.form-section-desc').exists()).toBe(false)
  })

  it('renders slot content in the body', () => {
    const w = shallowMount(FormSection, {
      slots: { default: '<div class="inner">Content</div>' },
    })
    expect(w.find('.form-section-body .inner').exists()).toBe(true)
  })

  it('renders as a fieldset element', () => {
    const w = shallowMount(FormSection)
    expect(w.element.tagName).toBe('FIELDSET')
  })
})
