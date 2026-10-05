import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import Donut from './Donut.vue'

describe('Donut', () => {
  it('renders the rounded percentage label by default', () => {
    const wrapper = mount(Donut, { props: { pct: 73.4 } })
    expect(wrapper.text()).toContain('73%')
  })

  it('omits the label when hideLabel is true', () => {
    const wrapper = mount(Donut, { props: { pct: 50, hideLabel: true } })
    expect(wrapper.text()).toBe('')
  })

  it('clamps values above 100 and below 0', () => {
    const high = mount(Donut, { props: { pct: 250 } })
    expect(high.text()).toContain('100%')
    const low = mount(Donut, { props: { pct: -10 } })
    expect(low.text()).toContain('0%')
  })

  it('applies the size prop to width and height', () => {
    const wrapper = mount(Donut, { props: { pct: 42, size: 96 } })
    const svg = wrapper.find('svg')
    expect(svg.attributes('width')).toBe('96')
    expect(svg.attributes('height')).toBe('96')
  })

  it('exposes an aria-label with the rounded percentage', () => {
    const wrapper = mount(Donut, { props: { pct: 88 } })
    expect(wrapper.find('svg').attributes('aria-label')).toBe('88%')
  })
})
