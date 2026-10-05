import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import TextArea from './TextArea.vue'
import type { FieldNode } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'bio', control: 'textarea', ...overrides }
}

describe('TextArea', () => {
  it('renders the current value', () => {
    const wrapper = mount(TextArea, {
      props: { value: 'My bio', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('textarea').element.value).toBe('My bio')
  })

  it('renders empty string when value is undefined', () => {
    const wrapper = mount(TextArea, {
      props: { value: undefined, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('textarea').element.value).toBe('')
  })

  it('emits update:value on input', async () => {
    const wrapper = mount(TextArea, {
      props: { value: '', node: makeNode(), readonly: false },
    })
    await wrapper.find('textarea').setValue('Updated bio')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables textarea when readonly', () => {
    const wrapper = mount(TextArea, {
      props: { value: '', node: makeNode(), readonly: true },
    })
    expect(wrapper.find('textarea').element.disabled).toBe(true)
  })
})
