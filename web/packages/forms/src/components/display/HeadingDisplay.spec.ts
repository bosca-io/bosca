import { describe, it, expect } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import HeadingDisplay from './HeadingDisplay.vue'
import type { DisplayNode } from '../../types'

function makeNode(overrides: Partial<DisplayNode> = {}): DisplayNode {
  return { type: 'display', control: 'heading', text: 'Section Title', ...overrides }
}

describe('HeadingDisplay', () => {
  it('renders an h3 by default', () => {
    const wrapper = shallowMount(HeadingDisplay, { props: { node: makeNode() } })
    expect(wrapper.find('h3').exists()).toBe(true)
    expect(wrapper.text()).toBe('Section Title')
  })

  it('renders the correct heading tag for each level', () => {
    for (let level = 1; level <= 6; level++) {
      const wrapper = shallowMount(HeadingDisplay, {
        props: { node: makeNode({ level }) },
      })
      expect(wrapper.find(`h${level}`).exists()).toBe(true)
    }
  })

  it('clamps level below 1 to h1', () => {
    const wrapper = shallowMount(HeadingDisplay, {
      props: { node: makeNode({ level: 0 }) },
    })
    expect(wrapper.find('h1').exists()).toBe(true)
  })

  it('clamps level above 6 to h6', () => {
    const wrapper = shallowMount(HeadingDisplay, {
      props: { node: makeNode({ level: 10 }) },
    })
    expect(wrapper.find('h6').exists()).toBe(true)
  })

  it('applies correct size class for h1', () => {
    const wrapper = shallowMount(HeadingDisplay, {
      props: { node: makeNode({ level: 1 }) },
    })
    expect(wrapper.find('h1').classes()).toContain('text-2xl')
  })

  it('applies correct size class for h6', () => {
    const wrapper = shallowMount(HeadingDisplay, {
      props: { node: makeNode({ level: 6 }) },
    })
    expect(wrapper.find('h6').classes()).toContain('text-xs')
  })
})
