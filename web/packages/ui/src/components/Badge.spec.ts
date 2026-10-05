import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Badge from './Badge.vue'

describe('Badge', () => {
  it('renders slot content', () => {
    const w = shallowMount(Badge, {
      props: { color: '#5ec5ff' },
      slots: { default: 'Active' },
    })
    expect(w.text()).toBe('Active')
  })

  it('applies transparent variant styles by default (solid=false)', () => {
    const w = shallowMount(Badge, {
      props: { color: '#5ec5ff' },
    })
    const style = w.attributes('style') || ''
    // happy-dom may not fully serialize color-mix; check that color is set
    expect(style).toContain('color: #5ec5ff')
    // The background should NOT be the solid color directly
    expect(style).not.toContain('background: #5ec5ff;')
  })

  it('applies solid background when solid=true', () => {
    const w = shallowMount(Badge, {
      props: { color: '#5ec5ff', solid: true },
    })
    const style = w.attributes('style') || ''
    expect(style).toContain('background: #5ec5ff')
    expect(style).toContain('color: #fff')
  })

  it('sets border with color', () => {
    const solid = shallowMount(Badge, { props: { color: '#ff0000', solid: true } })
    const style = solid.attributes('style') || ''
    expect(style).toContain('#ff0000')
    expect(style).toContain('border')
  })

  it('has the pill class', () => {
    const w = shallowMount(Badge, { props: { color: '#000' } })
    expect(w.classes()).toContain('pill')
  })
})
