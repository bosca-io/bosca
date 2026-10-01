import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import SelectControl from './SelectControl.vue'
import type { FieldNode, JsonSchemaProperty } from '../../types'

const SelectStub = defineComponent({
  props: ['modelValue', 'options', 'placeholder', 'disabled', 'loading', 'onSearch'],
  emits: ['update:modelValue'],
  template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><option v-for="opt in options" :key="opt.value" :value="opt.value">{{ opt.label }}</option></select>',
})

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'role', control: 'select', ...overrides }
}

const propertySchema: JsonSchemaProperty = {
  type: 'string',
  enum: ['admin', 'user', 'guest'],
}

describe('SelectControl', () => {
  it('renders an unset selection without inventing a value', () => {
    const wrapper = mountCtrl({ value: null, propertySchema })
    expect(wrapper.findComponent(SelectStub).props('modelValue')).toBe('')
  })

  function mountCtrl(props: Record<string, unknown>) {
    return mount(SelectControl, {
      props: { node: makeNode(), readonly: false, ...props },
      global: { stubs: { Select: SelectStub } },
    })
  }

  it('renders options from propertySchema enum', () => {
    const wrapper = mountCtrl({ value: '', propertySchema })
    const options = wrapper.findAll('option')
    expect(options.length).toBe(3)
    expect(options[0].text()).toBe('admin')
  })

  it('renders no options when propertySchema has no enum', () => {
    const wrapper = mountCtrl({ value: '', propertySchema: { type: 'string' } })
    expect(wrapper.findAll('option').length).toBe(0)
  })

  it('emits update:value on selection change', async () => {
    const wrapper = mountCtrl({ value: '', propertySchema })
    await wrapper.find('select').setValue('user')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables select when readonly', () => {
    const wrapper = mountCtrl({ value: '', propertySchema, readonly: true })
    expect((wrapper.find('select').element as HTMLSelectElement).disabled).toBe(true)
  })
})
