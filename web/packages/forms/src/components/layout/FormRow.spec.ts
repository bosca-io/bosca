import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '../../test-helpers'
import { registerControl } from '../../controls'
import FormRow from './FormRow.vue'
import { defineComponent } from 'vue'
import type { RowNode, JsonSchema, FieldNode } from '../../types'

const StubControl = defineComponent({
  props: ['value', 'node', 'propertySchema', 'readonly', 'error'],
  emits: ['update:value'],
  template: '<div class="stub">{{ value }}</div>',
})

const schema: JsonSchema = {
  type: 'object',
  properties: {
    first: { type: 'string' },
    last: { type: 'string' },
  },
}

beforeEach(() => {
  registerControl('test-row-ctrl', StubControl)
})

describe('FormRow', () => {
  it('renders children in a flex row', () => {
    const node: RowNode = {
      type: 'row',
      children: [
        { type: 'field', property: 'first', control: 'test-row-ctrl' } as FieldNode,
        { type: 'field', property: 'last', control: 'test-row-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(FormRow, {
      props: { node, schema, modelValue: { first: 'A', last: 'B' }, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-row').exists()).toBe(true)
    expect(wrapper.findAll('.bosca-form-field').length).toBe(2)
  })

  it('applies column width via grid-column style based on col prop', () => {
    const node: RowNode = {
      type: 'row',
      children: [
        { type: 'field', property: 'first', control: 'test-row-ctrl', col: 6 } as FieldNode,
        { type: 'field', property: 'last', control: 'test-row-ctrl', col: 6 } as FieldNode,
      ],
    }
    const wrapper = mount(FormRow, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    const divs = wrapper.findAll('.bosca-form-row > div')
    expect(divs[0].attributes('style')).toContain('grid-column: span 6')
    expect(divs[1].attributes('style')).toContain('grid-column: span 6')
  })

  it('uses span 12 when col is not specified', () => {
    const node: RowNode = {
      type: 'row',
      children: [
        { type: 'field', property: 'first', control: 'test-row-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(FormRow, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-row > div').attributes('style')).toContain('grid-column: span 12')
  })

  it('emits update:field events from child fields', async () => {
    const node: RowNode = {
      type: 'row',
      children: [
        { type: 'field', property: 'first', control: 'test-row-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(FormRow, {
      props: { node, schema, modelValue: { first: 'old' }, readonly: false, errors: {} },
    })

    const field = wrapper.findComponent({ name: 'FormField' })
    field.vm.$emit('update:field', 'first', 'new')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['first', 'new'])
  })
})
