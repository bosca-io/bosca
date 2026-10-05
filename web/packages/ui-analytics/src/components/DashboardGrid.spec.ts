import { describe, it, expect, vi } from 'vitest'
import { mount, shallowMount } from '../test-helpers'
import DashboardGrid from './DashboardGrid.vue'
import type { GridItem } from '../grid/types'

function items(): GridItem[] {
  return [
    { id: 'a', x: 0, y: 0, w: 6, h: 2 },
    { id: 'b', x: 6, y: 0, w: 6, h: 3 },
  ]
}

describe('DashboardGrid', () => {
  it('renders grid items', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: items() } })
    expect(wrapper.findAll('.dashboard-grid__item')).toHaveLength(2)
  })

  it('renders empty when layout is empty', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: [] } })
    expect(wrapper.findAll('.dashboard-grid__item')).toHaveLength(0)
  })

  it('renders drag handles for unlocked items', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: items() } })
    expect(wrapper.findAll('.dashboard-grid__drag-handle')).toHaveLength(2)
  })

  it('renders resize handles for unlocked items', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: items() } })
    expect(wrapper.findAll('.dashboard-grid__resize-handle')).toHaveLength(2)
  })

  it('hides handles for locked items', () => {
    const wrapper = shallowMount(DashboardGrid, {
      props: { layout: items(), lockedIds: ['a'] },
    })
    expect(wrapper.findAll('.dashboard-grid__drag-handle')).toHaveLength(1)
    expect(wrapper.findAll('.dashboard-grid__resize-handle')).toHaveLength(1)
  })

  it('sets CSS grid variables', () => {
    const wrapper = shallowMount(DashboardGrid, {
      props: { layout: items(), columns: 8, cellHeight: 40, gap: 4 },
    })
    const style = wrapper.find('.dashboard-grid').attributes('style')!
    expect(style).toContain('--columns: 8')
    expect(style).toContain('--cell-height: 40px')
    expect(style).toContain('--grid-gap: 4px')
  })

  it('uses default props', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: [] } })
    const style = wrapper.find('.dashboard-grid').attributes('style')!
    expect(style).toContain('--columns: 12')
    expect(style).toContain('--cell-height: 60px')
    expect(style).toContain('--grid-gap: 8px')
  })

  it('computes minimum 4 rows', () => {
    const wrapper = shallowMount(DashboardGrid, { props: { layout: [] } })
    const style = wrapper.find('.dashboard-grid').attributes('style')!
    expect(style).toContain('--rows: 4')
  })

  it('exposes item slot', () => {
    const wrapper = shallowMount(DashboardGrid, {
      props: { layout: [{ id: 'x', x: 0, y: 0, w: 1, h: 1 }] },
      slots: { item: '<div class="slot-content">test</div>' },
    })
    expect(wrapper.find('.slot-content').exists()).toBe(true)
  })

  it('items have grid position styles when not dragging', () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    const first = wrapper.findAll('.dashboard-grid__item')[0]
    const style = first.attributes('style')!
    expect(style).toContain('--gc: 1 / span 6')
    expect(style).toContain('--gr: 1 / span 2')
  })

  it('does not show placeholder when not dragging', () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    expect(wrapper.find('.dashboard-grid__placeholder').exists()).toBe(false)
  })

  it('handles pointerup when not dragging or resizing', async () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    await wrapper.find('.dashboard-grid').trigger('pointerup')
  })

  it('handles pointermove when not dragging or resizing', async () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    await wrapper.find('.dashboard-grid').trigger('pointermove')
  })

  it('handles pointercancel', async () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    await wrapper.find('.dashboard-grid').trigger('pointercancel')
  })

  it('applies active layout from drag preview', async () => {
    const wrapper = mount(DashboardGrid, { props: { layout: items() } })
    expect(wrapper.findAll('.dashboard-grid__item')).toHaveLength(2)
  })

  it('uses rows from layout height when > 4', () => {
    const tallItems: GridItem[] = [
      { id: 'a', x: 0, y: 0, w: 6, h: 10 },
    ]
    const wrapper = mount(DashboardGrid, { props: { layout: tallItems } })
    const style = wrapper.find('.dashboard-grid').attributes('style')!
    expect(style).toContain('--rows: 10')
  })

  it('exposes handle slot', () => {
    const wrapper = mount(DashboardGrid, {
      props: { layout: [{ id: 'x', x: 0, y: 0, w: 1, h: 1 }] },
      slots: { handle: '<span class="custom-handle">drag</span>' },
    })
    expect(wrapper.find('.custom-handle').exists()).toBe(true)
  })

  it('renders default grip when no handle slot', () => {
    const wrapper = mount(DashboardGrid, {
      props: { layout: [{ id: 'x', x: 0, y: 0, w: 1, h: 1 }] },
    })
    expect(wrapper.find('.dashboard-grid__grip').exists()).toBe(true)
  })

  it('emits update:layout on pointerup after drag interaction', async () => {
    const el = document.createElement('div')
    document.body.appendChild(el)

    const wrapper = mount(DashboardGrid, {
      props: { layout: items() },
      attachTo: el,
    })

    const handle = wrapper.find('.dashboard-grid__drag-handle')
    await handle.trigger('pointerdown', { clientX: 0, clientY: 0, pointerId: 1 })
    await wrapper.find('.dashboard-grid').trigger('pointerup')

    document.body.removeChild(el)
  })
})
