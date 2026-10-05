import { describe, it, expect } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import HelpText from './HelpText.vue'
import type { DisplayNode } from '../../types'

describe('HelpText', () => {
  it('renders the text content in a paragraph', () => {
    const node: DisplayNode = { type: 'display', control: 'help-text', text: 'Fill out all fields.' }
    const wrapper = shallowMount(HelpText, { props: { node } })
    expect(wrapper.find('p').text()).toBe('Fill out all fields.')
  })

  it('applies help-text styling', () => {
    const node: DisplayNode = { type: 'display', control: 'help-text', text: 'Hint' }
    const wrapper = shallowMount(HelpText, { props: { node } })
    expect(wrapper.find('p').classes()).toContain('help-text')
  })
})
