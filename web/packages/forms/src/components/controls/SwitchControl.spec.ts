import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import SwitchControl from './SwitchControl.vue'
import type { FieldNode } from '../../types'

const SwitchStub = defineComponent({
  props: ['modelValue', 'label', 'accent', 'disabled'],
  emits: ['update:modelValue'],
  template: '<button role="switch" :aria-checked="String(!!modelValue)" :disabled="disabled" @click="$emit(\'update:modelValue\', !modelValue)">switch</button>',
})

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'active', control: 'switch', ...overrides }
}

describe('SwitchControl', () => {
  function mountCtrl(props: Record<string, unknown>) {
    return mount(SwitchControl, {
      props: { node: makeNode(), readonly: false, ...props },
      global: { stubs: { Switch: SwitchStub } },
    })
  }

  it('renders with aria-checked matching value', () => {
    const wrapper = mountCtrl({ value: true })
    expect(wrapper.find('[role="switch"]').attributes('aria-checked')).toBe('true')
  })

  it('coerces falsy value to false', () => {
    const wrapper = mountCtrl({ value: null })
    expect(wrapper.find('[role="switch"]').attributes('aria-checked')).toBe('false')
  })

  it('emits update:value on click', async () => {
    const wrapper = mountCtrl({ value: false })
    await wrapper.find('[role="switch"]').trigger('click')
    expect(wrapper.emitted('update:value')).toBeTruthy()
    expect(wrapper.emitted('update:value')![0]).toEqual([true])
  })

  it('disables switch when readonly', () => {
    const wrapper = mountCtrl({ value: false, readonly: true })
    expect((wrapper.find('[role="switch"]').element as HTMLButtonElement).disabled).toBe(true)
  })
})
