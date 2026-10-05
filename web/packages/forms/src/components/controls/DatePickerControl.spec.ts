import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import DatePickerControl from './DatePickerControl.vue'
import type { FieldNode } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'dob', control: 'date-picker', ...overrides }
}

describe('DatePickerControl', () => {
  it('renders the current date value', () => {
    const wrapper = mount(DatePickerControl, {
      props: { value: '2025-01-15', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input').element.value).toBe('2025-01-15')
  })

  it('renders empty string when value is undefined', () => {
    const wrapper = mount(DatePickerControl, {
      props: { value: undefined, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input').element.value).toBe('')
  })

  it('emits update:value on change', async () => {
    const wrapper = mount(DatePickerControl, {
      props: { value: '', node: makeNode(), readonly: false },
    })
    await wrapper.find('input').setValue('2025-06-01')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables input when readonly', () => {
    const wrapper = mount(DatePickerControl, {
      props: { value: '', node: makeNode(), readonly: true },
    })
    expect(wrapper.find('input').element.disabled).toBe(true)
  })
})
