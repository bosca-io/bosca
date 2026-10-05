import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import SectionCard from './SectionCard.vue'

describe('SectionCard', () => {
  it('renders the title', () => {
    const w = shallowMount(SectionCard, { props: { title: 'Settings' } })
    expect(w.find('.section-title').text()).toBe('Settings')
  })

  it('renders default slot content', () => {
    const w = shallowMount(SectionCard, {
      props: { title: 'T' },
      slots: { default: '<div class="content">Body</div>' },
    })
    expect(w.find('.content').exists()).toBe(true)
    expect(w.find('.content').text()).toBe('Body')
  })

  it('renders right slot in the header', () => {
    const w = shallowMount(SectionCard, {
      props: { title: 'T' },
      slots: { right: '<button>Edit</button>' },
    })
    expect(w.find('.section-header').text()).toContain('Edit')
  })

  it('adds glass class when glass is true', () => {
    const w = shallowMount(SectionCard, { props: { title: 'T', glass: true } })
    expect(w.classes()).toContain('section-card--glass')
  })

  it('does not add glass class by default', () => {
    const w = shallowMount(SectionCard, { props: { title: 'T' } })
    expect(w.classes()).not.toContain('section-card--glass')
  })

  it('wraps the default slot in a .section-body when padded', () => {
    const w = shallowMount(SectionCard, {
      props: { title: 'T', padded: true },
      slots: { default: '<div class="content">Body</div>' },
    })
    expect(w.find('.section-body .content').exists()).toBe(true)
  })

  it('renders the default slot without a body wrapper by default', () => {
    const w = shallowMount(SectionCard, {
      props: { title: 'T' },
      slots: { default: '<div class="content">Body</div>' },
    })
    expect(w.find('.section-body').exists()).toBe(false)
    expect(w.find('.content').exists()).toBe(true)
  })

  it('uses the #header slot in place of the default title bar when provided', () => {
    const w = shallowMount(SectionCard, {
      props: { title: 'Ignored' },
      slots: { header: '<h3>Custom header</h3>' },
    })
    expect(w.find('.section-header--slot').text()).toContain('Custom header')
    expect(w.find('.section-title').exists()).toBe(false)
  })

  it('omits the entire header when neither title nor header-related slots are provided', () => {
    const w = shallowMount(SectionCard, {
      slots: { default: '<div>Body</div>' },
    })
    expect(w.find('.section-header').exists()).toBe(false)
  })
})
