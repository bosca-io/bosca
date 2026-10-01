import { describe, it, expect } from 'vitest'
import { mount } from '../test-helpers'
import Icon from './Icon.vue'

function mountIcon(props: Record<string, unknown> = {}) {
  return mount(Icon, { props })
}

describe('Icon', () => {
  it('renders SVG with viewBox', () => {
    const w = mountIcon({ name: 'dashboard' })
    const svg = w.find('svg')
    expect(svg.exists()).toBe(true)
    expect((svg.element as SVGSVGElement).getAttribute('viewBox')).toBe('0 0 24 24')
  })

  it('applies size to width and height', () => {
    const w = mountIcon({ size: 24 })
    const svg = w.find('svg')
    expect(svg.attributes('width')).toBe('24')
    expect(svg.attributes('height')).toBe('24')
  })

  it('uses default size of 18', () => {
    const w = mountIcon()
    const svg = w.find('svg')
    expect(svg.attributes('width')).toBe('18')
    expect(svg.attributes('height')).toBe('18')
  })

  it('applies color to stroke', () => {
    const w = mountIcon({ color: '#ff0000' })
    const svg = w.find('svg')
    expect(svg.attributes('stroke')).toBe('#ff0000')
  })

  it('uses default color of currentColor', () => {
    const w = mountIcon()
    const svg = w.find('svg')
    expect(svg.attributes('stroke')).toBe('currentColor')
  })

  it('different icon names render different paths', () => {
    const w1 = mountIcon({ name: 'dashboard' })
    const w2 = mountIcon({ name: 'search' })
    expect(w1.find('svg').html()).not.toBe(w2.find('svg').html())
  })

  it('unknown icon renders fallback (dashboard)', () => {
    const w = mountIcon({ name: 'nonexistent-icon-xyz' })
    const wDefault = mountIcon({ name: 'dashboard' })
    // Unknown icon falls back to dashboard
    expect(w.find('svg').html()).toBe(wDefault.find('svg').html())
  })

  it('uses default name of dashboard', () => {
    const w = mountIcon()
    const wDash = mountIcon({ name: 'dashboard' })
    expect(w.find('svg').html()).toBe(wDash.find('svg').html())
  })
})
