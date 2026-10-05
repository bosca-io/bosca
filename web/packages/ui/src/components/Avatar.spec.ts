import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import Avatar from './Avatar.vue'

describe('Avatar', () => {
  it('renders initials from a two-word name', () => {
    const w = shallowMount(Avatar, { props: { name: 'Alex User' } })
    expect(w.text()).toBe('AU')
  })

  it('renders single initial from a one-word name', () => {
    const w = shallowMount(Avatar, { props: { name: 'Alex' } })
    expect(w.text()).toBe('A')
  })

  it('renders "?" when name is empty', () => {
    const w = shallowMount(Avatar, { props: { name: '' } })
    expect(w.text()).toBe('?')
  })

  it('limits initials to two characters', () => {
    const w = shallowMount(Avatar, { props: { name: 'John Paul Jones' } })
    expect(w.text()).toBe('JP')
  })

  it('uppercases initials', () => {
    const w = shallowMount(Avatar, { props: { name: 'john doe' } })
    expect(w.text()).toBe('JD')
  })

  it('defaults size to 22px', () => {
    const w = shallowMount(Avatar, { props: { name: 'A' } })
    const style = w.attributes('style')
    expect(style).toContain('width: 22px')
    expect(style).toContain('height: 22px')
  })

  it('applies custom size', () => {
    const w = shallowMount(Avatar, { props: { name: 'A', size: 40 } })
    const style = w.attributes('style')
    expect(style).toContain('width: 40px')
    expect(style).toContain('height: 40px')
  })

  it('computes fontSize as size * 0.42 rounded', () => {
    const w = shallowMount(Avatar, { props: { name: 'A', size: 40 } })
    // 40 * 0.42 = 16.8 -> 17
    expect(w.attributes('style')).toContain('font-size: 17px')
  })

  it('cycles through palette based on idx', () => {
    const palette = [
      '#ff9b5c', '#9d7cff', '#5ec5ff', '#34d99a',
      '#ffb547', '#ff7ac6', '#06b6d4', '#ff5d6c',
    ]
    for (let i = 0; i < palette.length; i++) {
      const w = shallowMount(Avatar, { props: { name: 'A', idx: i } })
      expect(w.attributes('style')).toContain(`background: ${palette[i]}`)
    }
  })

  it('wraps idx around the palette length', () => {
    const w0 = shallowMount(Avatar, { props: { name: 'A', idx: 0 } })
    const w8 = shallowMount(Avatar, { props: { name: 'A', idx: 8 } })
    expect(w0.attributes('style')).toContain(w8.attributes('style')!.match(/background: (#\w+)/)![1])
  })

  it('defaults idx to 0', () => {
    const w = shallowMount(Avatar, { props: { name: 'A' } })
    expect(w.attributes('style')).toContain('background: #ff9b5c')
  })
})
