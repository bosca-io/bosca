import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import NumberInput from './NumberInput.vue'
import type { FieldNode } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'age', control: 'number-input', ...overrides }
}

describe('NumberInput', () => {
  it('represents an unset numeric value without substituting zero', () => {
    const wrapper = mount(NumberInput, { props: { value: undefined, node: makeNode(), readonly: false } })
    expect(wrapper.find<HTMLInputElement>('input[type="number"]').element.value).toBe('')
  })

  it('renders the current numeric value', () => {
    const wrapper = mount(NumberInput, {
      props: { value: 25, node: makeNode(), readonly: false },
    })
    expect(wrapper.find<HTMLInputElement>('input[type="number"]').element.value).toBe('25')
  })

  it('emits update:value on input', async () => {
    const wrapper = mount(NumberInput, {
      props: { value: 0, node: makeNode(), readonly: false },
    })
    await wrapper.find('input[type="number"]').setValue('42')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables input when readonly', () => {
    const wrapper = mount(NumberInput, {
      props: { value: 5, node: makeNode(), readonly: true },
    })
    expect(wrapper.find<HTMLInputElement>('input[type="number"]').element.disabled).toBe(true)
  })
})
