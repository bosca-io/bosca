import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import StatGrid from './StatGrid.vue'

describe('StatGrid', () => {
  it('renders slot content', () => {
    const w = shallowMount(StatGrid, {
      slots: { default: '<div class="tile">Tile</div>' },
    })
    expect(w.find('.tile').exists()).toBe(true)
  })

  it('defaults to 4 columns', () => {
    const w = shallowMount(StatGrid)
    expect(w.attributes('style')).toContain('grid-template-columns: repeat(4, 1fr)')
  })

  it('applies custom column count', () => {
    const w = shallowMount(StatGrid, { props: { columns: 3 } })
    expect(w.attributes('style')).toContain('grid-template-columns: repeat(3, 1fr)')
  })

  it('has stat-grid class', () => {
    const w = shallowMount(StatGrid)
    expect(w.classes()).toContain('stat-grid')
  })
})
