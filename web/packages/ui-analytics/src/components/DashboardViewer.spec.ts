import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import DashboardViewer from './DashboardViewer.vue'
import type { GridItem } from '../grid/types'

function items(): GridItem[] {
  return [
    { id: 'a', x: 0, y: 0, w: 6, h: 2 },
    { id: 'b', x: 6, y: 0, w: 6, h: 3 },
  ]
}

describe('DashboardViewer', () => {
  it('renders a grid item for each layout entry', () => {
    const wrapper = shallowMount(DashboardViewer, { props: { layout: items() } })
    expect(wrapper.findAll('.dashboard-viewer__item')).toHaveLength(2)
  })

  it('renders empty when layout is empty', () => {
    const wrapper = shallowMount(DashboardViewer, { props: { layout: [] } })
    expect(wrapper.findAll('.dashboard-viewer__item')).toHaveLength(0)
  })

  it('sets CSS grid variables', () => {
    const wrapper = shallowMount(DashboardViewer, {
      props: { layout: items(), columns: 8, cellHeight: 40, gap: 4 },
    })
    const style = wrapper.find('.dashboard-viewer').attributes('style')!
    expect(style).toContain('--columns: 8')
    expect(style).toContain('--cell-height: 40px')
    expect(style).toContain('--grid-gap: 4px')
  })

  it('positions items using grid-column and grid-row', () => {
    const wrapper = shallowMount(DashboardViewer, { props: { layout: items() } })
    const first = wrapper.findAll('.dashboard-viewer__item')[0]
    const style = first.attributes('style')!
    expect(style).toContain('grid-column: 1 / span 6')
    expect(style).toContain('grid-row: 1 / span 2')
  })

  it('computes rows from layout', () => {
    const wrapper = shallowMount(DashboardViewer, { props: { layout: items() } })
    const style = wrapper.find('.dashboard-viewer').attributes('style')!
    expect(style).toContain('--rows: 3')
  })

  it('uses default props', () => {
    const wrapper = shallowMount(DashboardViewer, { props: { layout: [] } })
    const style = wrapper.find('.dashboard-viewer').attributes('style')!
    expect(style).toContain('--columns: 12')
    expect(style).toContain('--cell-height: 60px')
    expect(style).toContain('--grid-gap: 8px')
  })

  it('exposes item slot', () => {
    const wrapper = shallowMount(DashboardViewer, {
      props: { layout: [{ id: 'x', x: 0, y: 0, w: 1, h: 1 }] },
      slots: { item: '<div class="slot-content">test</div>' },
    })
    expect(wrapper.find('.slot-content').exists()).toBe(true)
  })
})
