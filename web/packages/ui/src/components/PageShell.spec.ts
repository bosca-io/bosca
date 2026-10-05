import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import PageShell from './PageShell.vue'

function mountShell(slots?: Record<string, string>) {
  return shallowMount(PageShell, { slots })
}

describe('PageShell', () => {
  it('renders header slot', () => {
    const w = mountShell({ header: '<div class="test-header">Header</div>' })
    expect(w.find('.test-header').exists()).toBe(true)
    expect(w.text()).toContain('Header')
  })

  it('renders default slot', () => {
    const w = mountShell({ default: '<p>Main content</p>' })
    expect(w.find('.page-content').text()).toContain('Main content')
  })

  it('renders page-shell wrapper', () => {
    const w = mountShell()
    expect(w.find('.page-shell').exists()).toBe(true)
  })
})
