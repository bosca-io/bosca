import { describe, it, expect } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import AlertDisplay from './AlertDisplay.vue'
import type { DisplayNode } from '../../types'

function makeNode(overrides: Partial<DisplayNode> = {}): DisplayNode {
  return { type: 'display', control: 'alert', text: 'Test alert', ...overrides }
}

describe('AlertDisplay', () => {
  it('falls back to info styling for an unknown persisted variant', () => {
    const wrapper = shallowMount(AlertDisplay, { props: { node: makeNode({ variant: 'unknown' }) } })
    expect(wrapper.find('.alert').attributes('style')).toContain('var(--info)')
  })

  it('renders the alert text', () => {
    const wrapper = shallowMount(AlertDisplay, { props: { node: makeNode() } })
    expect(wrapper.text()).toContain('Test alert')
  })

  it('applies info (default) variant styling', () => {
    const wrapper = shallowMount(AlertDisplay, { props: { node: makeNode() } })
    const style = wrapper.find('.alert').attributes('style')!
    expect(style).toContain('var(--info)')
  })

  it('applies warning variant styling', () => {
    const wrapper = shallowMount(AlertDisplay, {
      props: { node: makeNode({ variant: 'warning' }) },
    })
    const style = wrapper.find('.alert').attributes('style')!
    expect(style).toContain('var(--warn)')
  })

  it('applies error variant styling', () => {
    const wrapper = shallowMount(AlertDisplay, {
      props: { node: makeNode({ variant: 'error' }) },
    })
    const style = wrapper.find('.alert').attributes('style')!
    expect(style).toContain('var(--err)')
  })

  it('applies success variant styling', () => {
    const wrapper = shallowMount(AlertDisplay, {
      props: { node: makeNode({ variant: 'success' }) },
    })
    const style = wrapper.find('.alert').attributes('style')!
    expect(style).toContain('var(--ok)')
  })
})
