import { describe, it, expect } from 'vitest'
import { mount } from '../../test-helpers'
import { defineComponent } from 'vue'
import { TagInput as BoscaTagInput } from '@bosca/ui'
import TagInput from './TagInput.vue'
import type { FieldNode } from '../../types'

const BoscaTagInputStub = defineComponent({
  props: ['modelValue', 'placeholder', 'disabled'],
  emits: ['update:modelValue'],
  template: '<div class="tag-input-wrap"><span v-for="t in modelValue" :key="t">{{ t }}</span></div>',
})

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'tags', control: 'tag-input', ...overrides }
}

describe('TagInput', () => {
  it('forwards edited tag values to the form', () => {
    const wrapper = mount(TagInput, {
      props: { value: [], node: makeNode(), readonly: false },
      global: { stubs: { BoscaTagInput: BoscaTagInputStub } },
    })
    wrapper.findComponent(BoscaTagInput).vm.$emit('update:modelValue', ['new'])
    expect(wrapper.emitted('update:value')).toEqual([[['new']]])
  })

  function mountCtrl(props: Record<string, unknown>) {
    return mount(TagInput, {
      props: { node: makeNode(), readonly: false, ...props },
      global: { stubs: { BoscaTagInput: BoscaTagInputStub } },
    })
  }

  it('renders existing tags', () => {
    const wrapper = mountCtrl({ value: ['alpha', 'beta'] })
    expect(wrapper.text()).toContain('alpha')
    expect(wrapper.text()).toContain('beta')
  })

  it('handles non-array value gracefully', () => {
    const wrapper = mountCtrl({ value: 'not-an-array' })
    // Should not crash, tags computed returns []
    expect(wrapper.find('.tag-input-wrap').exists()).toBe(true)
  })

  it('handles undefined value', () => {
    const wrapper = mountCtrl({ value: undefined })
    expect(wrapper.find('.tag-input-wrap').exists()).toBe(true)
  })
})
