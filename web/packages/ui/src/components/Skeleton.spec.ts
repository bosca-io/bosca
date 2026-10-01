import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Skeleton from './Skeleton.vue'

describe('Skeleton', () => {
  it('renders a single line by default', () => {
    const w = shallowMount(Skeleton)
    expect(w.findAll('.skeleton')).toHaveLength(1)
  })

  it('renders the specified number of lines', () => {
    const w = shallowMount(Skeleton, { props: { lines: 3 } })
    expect(w.findAll('.skeleton')).toHaveLength(3)
  })

  it('single line uses full width by default', () => {
    const w = shallowMount(Skeleton, { props: { lines: 1 } })
    expect(w.find('.skeleton').attributes('style')).toContain('width: 100%')
  })

  it('last line is 60% width when lines > 1', () => {
    const w = shallowMount(Skeleton, { props: { lines: 3 } })
    const skeletons = w.findAll('.skeleton')
    expect(skeletons[2].attributes('style')).toContain('width: 60%')
  })

  it('non-last lines use full width when lines > 1', () => {
    const w = shallowMount(Skeleton, { props: { lines: 3 } })
    const skeletons = w.findAll('.skeleton')
    expect(skeletons[0].attributes('style')).toContain('width: 100%')
    expect(skeletons[1].attributes('style')).toContain('width: 100%')
  })

  it('applies custom width to single line', () => {
    const w = shallowMount(Skeleton, { props: { width: '200px' } })
    expect(w.find('.skeleton').attributes('style')).toContain('width: 200px')
  })

  it('applies custom height', () => {
    const w = shallowMount(Skeleton, { props: { height: '24px' } })
    expect(w.find('.skeleton').attributes('style')).toContain('height: 24px')
  })

  it('applies custom radius', () => {
    const w = shallowMount(Skeleton, { props: { radius: '8px' } })
    expect(w.find('.skeleton').attributes('style')).toContain('border-radius: 8px')
  })
})
