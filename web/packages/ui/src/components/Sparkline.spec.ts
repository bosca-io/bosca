import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Sparkline from './Sparkline.vue'

describe('Sparkline', () => {
  const sampleValues = [0, 5, 3, 8, 2]

  it('renders an SVG element', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues } })
    expect(w.find('svg').exists()).toBe(true)
  })

  it('sets correct viewBox from width and height props', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues, width: 100, height: 40 } })
    const svg = w.find('svg')
    expect(svg.attributes('viewBox') || svg.attributes('viewbox')).toBe('0 0 100 40')
  })

  it('uses default width=80 and height=28', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues } })
    const svg = w.find('svg')
    expect(svg.attributes('width')).toBe('80')
    expect(svg.attributes('height')).toBe('28')
    expect(svg.attributes('viewBox') || svg.attributes('viewbox')).toBe('0 0 80 28')
  })

  it('renders a polyline with stroke (the line)', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues } })
    const polylines = w.findAll('polyline')
    const strokeLine = polylines.find(p => p.attributes('fill') === 'none')
    expect(strokeLine).toBeDefined()
    expect(strokeLine!.attributes('points')).toBeTruthy()
  })

  it('renders a fill polyline', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues } })
    const polylines = w.findAll('polyline')
    const fillLine = polylines.find(p => p.attributes('stroke') === 'none')
    expect(fillLine).toBeDefined()
  })

  it('computes correct point coordinates', () => {
    // values [0, 10], width=80, height=28
    // max=10, min=0, range=10; 1px inset top+bottom -> pad=1, drawable=28-2=26
    // point 0: x = (0/1)*80 = 0, y = 1 + 26 - ((0-0)/10)*26 = 27 -> "0,27"
    // point 1: x = (1/1)*80 = 80, y = 1 + 26 - ((10-0)/10)*26 = 1 -> "80,1"
    const w = shallowMount(Sparkline, { props: { values: [0, 10], width: 80, height: 28 } })
    const polylines = w.findAll('polyline')
    const strokeLine = polylines.find(p => p.attributes('fill') === 'none')!
    expect(strokeLine.attributes('points')).toBe('0,27 80,1')
  })

  it('applies accent color to the stroke', () => {
    const w = shallowMount(Sparkline, { props: { values: sampleValues, accent: '#ff0000' } })
    const polylines = w.findAll('polyline')
    const strokeLine = polylines.find(p => p.attributes('fill') === 'none')!
    expect(strokeLine.attributes('stroke')).toBe('#ff0000')
  })
})
