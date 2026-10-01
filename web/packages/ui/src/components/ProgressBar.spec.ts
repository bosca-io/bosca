import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import ProgressBar from './ProgressBar.vue'

describe('ProgressBar', () => {
  it('sets fill width to match value', () => {
    const w = shallowMount(ProgressBar, { props: { value: 42 } })
    expect(w.find('.bar-fill').attributes('style')).toContain('width: 42%')
  })

  it('clamps value to 0 when negative', () => {
    const w = shallowMount(ProgressBar, { props: { value: -10 } })
    expect(w.find('.bar-fill').attributes('style')).toContain('width: 0%')
  })

  it('clamps value to 100 when above 100', () => {
    const w = shallowMount(ProgressBar, { props: { value: 150 } })
    expect(w.find('.bar-fill').attributes('style')).toContain('width: 100%')
  })

  it('renders sub text when provided', () => {
    const w = shallowMount(ProgressBar, { props: { value: 50, sub: '50%' } })
    expect(w.find('.bar-sub').text()).toBe('50%')
  })

  it('hides sub element when not provided', () => {
    const w = shallowMount(ProgressBar, { props: { value: 50 } })
    expect(w.find('.bar-sub').exists()).toBe(false)
  })

  it('applies custom height to the track', () => {
    const w = shallowMount(ProgressBar, { props: { value: 50, height: 12 } })
    expect(w.find('.bar-track').attributes('style')).toContain('height: 12px')
  })

  it('defaults height to 6px', () => {
    const w = shallowMount(ProgressBar, { props: { value: 50 } })
    expect(w.find('.bar-track').attributes('style')).toContain('height: 6px')
  })

  it('applies accent color to the fill', () => {
    const w = shallowMount(ProgressBar, { props: { value: 50, accent: '#ff0000' } })
    expect(w.find('.bar-fill').attributes('style')).toContain('background: #ff0000')
  })
})
