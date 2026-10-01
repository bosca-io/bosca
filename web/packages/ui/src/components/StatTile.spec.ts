import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import StatTile from './StatTile.vue'

describe('StatTile', () => {
  it('renders label', () => {
    const w = shallowMount(StatTile, { props: { label: 'Users', value: '1,234' } })
    expect(w.find('.stat-label').text()).toBe('Users')
  })

  it('renders value', () => {
    const w = shallowMount(StatTile, { props: { label: 'Users', value: '1,234' } })
    expect(w.find('.stat-value').text()).toBe('1,234')
  })

  it('renders sub text when provided', () => {
    const w = shallowMount(StatTile, { props: { label: 'Users', value: '100', sub: '+12%' } })
    expect(w.find('.stat-sub').text()).toBe('+12%')
  })

  it('hides sub when not provided', () => {
    const w = shallowMount(StatTile, { props: { label: 'Users', value: '100' } })
    expect(w.find('.stat-sub').exists()).toBe(false)
  })

  it('applies accent color to sub text', () => {
    const w = shallowMount(StatTile, {
      props: { label: 'L', value: 'V', sub: '+5%', accent: '#34d99a' },
    })
    expect(w.find('.stat-sub').attributes('style')).toContain('color: #34d99a')
  })

  it('uses fallback color when accent is not provided', () => {
    const w = shallowMount(StatTile, {
      props: { label: 'L', value: 'V', sub: '+5%' },
    })
    expect(w.find('.stat-sub').attributes('style')).toContain('color: var(--fg-3)')
  })
})
