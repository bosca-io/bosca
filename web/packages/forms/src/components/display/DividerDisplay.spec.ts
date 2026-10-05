import { describe, it, expect } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import DividerDisplay from './DividerDisplay.vue'
import type { DisplayNode } from '../../types'

describe('DividerDisplay', () => {
  it('renders an hr element', () => {
    const node: DisplayNode = { type: 'display', control: 'divider' }
    const wrapper = shallowMount(DividerDisplay, { props: { node } })
    expect(wrapper.find('hr').exists()).toBe(true)
  })
})
