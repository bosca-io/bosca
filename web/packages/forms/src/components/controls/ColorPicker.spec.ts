import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import ColorPicker from './ColorPicker.vue'
import type { FieldNode } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'color', control: 'color-picker', ...overrides }
}

describe('ColorPicker', () => {
  it('renders the current color value', () => {
    const wrapper = mount(ColorPicker, {
      props: { value: '#ff0000', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="color"]').element.value).toBe('#ff0000')
  })

  it('defaults to #000000 when value is undefined', () => {
    const wrapper = mount(ColorPicker, {
      props: { value: undefined, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="color"]').element.value).toBe('#000000')
  })

  it('emits update:value on input', async () => {
    const wrapper = mount(ColorPicker, {
      props: { value: '#000000', node: makeNode(), readonly: false },
    })
    await wrapper.find('input[type="color"]').setValue('#00ff00')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables input when readonly', () => {
    const wrapper = mount(ColorPicker, {
      props: { value: '#000000', node: makeNode(), readonly: true },
    })
    expect(wrapper.find('input[type="color"]').element.disabled).toBe(true)
  })
})
