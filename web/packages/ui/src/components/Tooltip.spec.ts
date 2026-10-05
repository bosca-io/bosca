import { describe, it, expect, vi } from 'vitest'
import { shallowMount } from '../test-helpers'
import Tooltip from './Tooltip.vue'

vi.mock('tippy.js', () => ({
  default: vi.fn(() => ({ show: vi.fn(), hide: vi.fn(), destroy: vi.fn() })),
}))

function mountTooltip(props: Record<string, unknown> = {}, slots?: Record<string, string>) {
  return shallowMount(Tooltip, {
    props: { content: 'Helpful tip', ...props },
    slots: { default: '<span>Hover me</span>', ...slots },
  })
}

describe('Tooltip', () => {
  it('renders slot content', () => {
    const w = mountTooltip()
    expect(w.text()).toContain('Hover me')
  })

  it('sets data-tippy-content attribute', () => {
    const w = mountTooltip({ content: 'My tooltip' })
    expect(w.find('.tooltip-trigger').attributes('data-tippy-content')).toBe('My tooltip')
  })

  it('sets data-tippy-placement attribute', () => {
    const w = mountTooltip({ placement: 'bottom' })
    expect(w.find('.tooltip-trigger').attributes('data-tippy-placement')).toBe('bottom')
  })

  it('sets default placement to top', () => {
    const w = mountTooltip()
    expect(w.find('.tooltip-trigger').attributes('data-tippy-placement')).toBe('top')
  })

  it('sets data-tippy-delay attribute', () => {
    const w = mountTooltip({ delay: 500 })
    expect(w.find('.tooltip-trigger').attributes('data-tippy-delay')).toBe('500')
  })

  it('uses default delay of 200', () => {
    const w = mountTooltip()
    expect(w.find('.tooltip-trigger').attributes('data-tippy-delay')).toBe('200')
  })
})
