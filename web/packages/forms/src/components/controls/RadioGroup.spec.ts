import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import RadioGroup from './RadioGroup.vue'
import type { FieldNode, JsonSchemaProperty } from '../../types'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'priority', control: 'radio-group', ...overrides }
}

const propertySchema: JsonSchemaProperty = {
  type: 'string',
  enum: ['low', 'medium', 'high'],
}

describe('RadioGroup', () => {
  it('supports an unset value and emits the selected option', async () => {
    const wrapper = mount(RadioGroup, { props: { value: null, node: makeNode(), propertySchema, readonly: false } })
    await wrapper.find('input[value="high"]').setValue()
    expect(wrapper.emitted('update:value')).toEqual([['high']])
  })

  it('renders radio items from propertySchema enum', () => {
    const wrapper = mount(RadioGroup, {
      props: { value: '', node: makeNode(), propertySchema, readonly: false },
    })
    const radios = wrapper.findAll('input[type="radio"]')
    expect(radios.length).toBe(3)
  })

  it('renders labels for each option', () => {
    const wrapper = mount(RadioGroup, {
      props: { value: '', node: makeNode(), propertySchema, readonly: false },
    })
    expect(wrapper.text()).toContain('low')
    expect(wrapper.text()).toContain('medium')
    expect(wrapper.text()).toContain('high')
  })

  it('checks the radio matching the current value', () => {
    const wrapper = mount(RadioGroup, {
      props: { value: 'medium', node: makeNode(), propertySchema, readonly: false },
    })
    const radios = wrapper.findAll('input[type="radio"]')
    const checked = radios.find(r => (r.element as HTMLInputElement).checked)
    expect(checked).toBeTruthy()
    expect((checked!.element as HTMLInputElement).value).toBe('medium')
  })

  it('renders no items when propertySchema has no enum', () => {
    const wrapper = mount(RadioGroup, {
      props: { value: '', node: makeNode(), propertySchema: { type: 'string' }, readonly: false },
    })
    expect(wrapper.findAll('input[type="radio"]').length).toBe(0)
  })

  it('disables all radios when readonly', () => {
    const wrapper = mount(RadioGroup, {
      props: { value: '', node: makeNode(), propertySchema, readonly: true },
    })
    wrapper.findAll('input[type="radio"]').forEach(radio => {
      expect((radio.element as HTMLInputElement).disabled).toBe(true)
    })
  })
})
