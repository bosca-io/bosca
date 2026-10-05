import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import CheckboxControl from './CheckboxControl.vue'
import type { FieldNode } from '../../types'

const CheckboxStub = defineComponent({
  props: ['modelValue', 'label', 'disabled', 'accent', 'indeterminate'],
  emits: ['update:modelValue'],
  template: '<input type="checkbox" :checked="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', !modelValue)" />',
})

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'agree', control: 'checkbox', ...overrides }
}

describe('CheckboxControl', () => {
  function mountCtrl(props: Record<string, unknown>) {
    return mount(CheckboxControl, {
      props: { node: makeNode(), readonly: false, ...props },
      global: { stubs: { Checkbox: CheckboxStub } },
    })
  }

  it('renders checked when value is true', () => {
    const wrapper = mountCtrl({ value: true })
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(true)
  })

  it('renders unchecked when value is false', () => {
    const wrapper = mountCtrl({ value: false })
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(false)
  })

  it('coerces falsy value to false', () => {
    const wrapper = mountCtrl({ value: undefined })
    expect((wrapper.find('input').element as HTMLInputElement).checked).toBe(false)
  })

  it('emits update:value on toggle', async () => {
    const wrapper = mountCtrl({ value: false })
    await wrapper.find('input').trigger('change')
    expect(wrapper.emitted('update:value')).toBeTruthy()
  })

  it('disables checkbox when readonly', () => {
    const wrapper = mountCtrl({ value: false, readonly: true })
    expect((wrapper.find('input').element as HTMLInputElement).disabled).toBe(true)
  })
})
