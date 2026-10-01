import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Card from './Card.vue'

describe('Card', () => {
  it('renders the title', () => {
    const w = shallowMount(Card, { props: { title: 'Metrics' } })
    expect(w.find('.card-title').text()).toBe('Metrics')
  })

  it('renders default slot content in the body', () => {
    const w = shallowMount(Card, {
      props: { title: 'T' },
      slots: { default: '<p>Body content</p>' },
    })
    expect(w.find('.card-body').text()).toBe('Body content')
  })

  it('renders the right slot in the header', () => {
    const w = shallowMount(Card, {
      props: { title: 'T' },
      slots: { right: '<button>Action</button>' },
    })
    expect(w.find('.card-header').text()).toContain('Action')
  })

  it('adds card--glass class when glass is true', () => {
    const w = shallowMount(Card, { props: { title: 'T', glass: true } })
    expect(w.classes()).toContain('card--glass')
  })

  it('does not add card--glass class by default', () => {
    const w = shallowMount(Card, { props: { title: 'T' } })
    expect(w.classes()).not.toContain('card--glass')
  })

  it('applies padding to body when padded is true (default)', () => {
    const w = shallowMount(Card, { props: { title: 'T' } })
    expect(w.find('.card-body').attributes('style')).toContain('padding: 14px')
  })

  it('removes body padding when padded is false', () => {
    const w = shallowMount(Card, { props: { title: 'T', padded: false } })
    expect(w.find('.card-body').attributes('style')).toContain('padding: 0')
  })
})
