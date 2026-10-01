import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '../../test-helpers'
import { registerControl } from '../../controls'
import FormField from './FormField.vue'
import { defineComponent } from 'vue'
import type { FieldNode, JsonSchema } from '../../types'

const StubControl = defineComponent({
  props: ['value', 'node', 'propertySchema', 'readonly', 'error'],
  emits: ['update:value'],
  template: '<div class="stub-control">{{ value }}</div>',
})

const schema: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string', minLength: 1 },
    bio: { type: 'string' },
  },
  required: ['name'],
}

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return {
    type: 'field',
    property: 'name',
    control: 'test-field-control',
    ...overrides,
  }
}

describe('FormField', () => {
  beforeEach(() => {
    registerControl('test-field-control', StubControl)
  })

  it('renders the resolved control component', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode(),
        schema,
        modelValue: { name: 'Alice' },
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.find('.stub-control').exists()).toBe(true)
    expect(wrapper.find('.stub-control').text()).toBe('Alice')
  })

  it('shows the property name as default label', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode(),
        schema,
        modelValue: {},
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.find('label').text()).toContain('name')
  })

  it('uses label override from node', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode({ label: 'Full Name' }),
        schema,
        modelValue: {},
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.find('label').text()).toContain('Full Name')
  })

  it('shows required indicator for required fields', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode({ property: 'name' }),
        schema,
        modelValue: {},
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.find('label').text()).toContain('*')
  })

  it('does not show required indicator for optional fields', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode({ property: 'bio' }),
        schema,
        modelValue: {},
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.find('label span')).toBeDefined()
    // bio is not required, so no red asterisk
    const labelHtml = wrapper.find('label').html()
    expect(labelHtml).not.toContain('text-red-500')
  })

  it('displays error message when present', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode(),
        schema,
        modelValue: {},
        readonly: false,
        errors: { name: 'Name is required' },
      },
    })
    expect(wrapper.text()).toContain('Name is required')
  })

  it('shows unknown control warning for unregistered controls', () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode({ control: 'nonexistent-widget' }),
        schema,
        modelValue: {},
        readonly: false,
        errors: {},
      },
    })
    expect(wrapper.text()).toContain('Unknown control: nonexistent-widget')
  })

  it('emits update:field when control emits update:value', async () => {
    const wrapper = mount(FormField, {
      props: {
        node: makeNode(),
        schema,
        modelValue: { name: 'Alice' },
        readonly: false,
        errors: {},
      },
    })

    const control = wrapper.findComponent(StubControl)
    control.vm.$emit('update:value', 'Bob')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['name', 'Bob'])
  })
})
