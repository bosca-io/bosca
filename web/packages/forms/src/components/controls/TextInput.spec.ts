import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import TextInput from './TextInput.vue'
import type { FieldNode } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'name', control: 'text-input', ...overrides }
}

describe('TextInput', () => {
  it('renders the current value', () => {
    const wrapper = mount(TextInput, {
      props: { value: 'Hello', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input').element.value).toBe('Hello')
  })

  it('renders empty string when value is undefined', () => {
    const wrapper = mount(TextInput, {
      props: { value: undefined, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input').element.value).toBe('')
  })

  it('emits update:value on input', async () => {
    const wrapper = mount(TextInput, {
      props: { value: '', node: makeNode(), readonly: false },
    })
    await wrapper.find('input').setValue('New value')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('passes placeholder from node', () => {
    const wrapper = mount(TextInput, {
      props: { value: '', node: makeNode({ placeholder: 'Enter name' }), readonly: false },
    })
    expect(wrapper.find('input').attributes('placeholder')).toBe('Enter name')
  })

  it('disables input when readonly', () => {
    const wrapper = mount(TextInput, {
      props: { value: '', node: makeNode(), readonly: true },
    })
    expect(wrapper.find('input').element.disabled).toBe(true)
  })
})
