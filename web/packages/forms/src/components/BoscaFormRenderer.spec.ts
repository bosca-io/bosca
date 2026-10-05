import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '../test-helpers'
import { registerControl } from '../controls'
import BoscaFormRenderer from './BoscaFormRenderer.vue'
import { defineComponent } from 'vue'
import type { JsonSchema, UiSchema, FieldNode, SectionNode, RowNode, DisplayNode } from '../types'

const StubControl = defineComponent({
  props: ['value', 'node', 'propertySchema', 'readonly', 'error'],
  emits: ['update:value'],
  template: '<div class="stub-renderer">{{ value }}</div>',
})

const schema: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    email: { type: 'string', format: 'email' },
  },
  required: ['name'],
}

beforeEach(() => {
  registerControl('test-renderer-ctrl', StubControl)
})

describe('BoscaFormRenderer', () => {
  it.each(['section', 'row'] as const)('forwards nested updates from a %s', type => {
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema: { version: 1, layout: [{ type, children: [] }] }, modelValue: {}, readonly: false, errors: {} },
    })
    wrapper.findComponent({ name: type === 'section' ? 'FormSection' : 'FormRow' }).vm.$emit('update:field', 'name', 'Ada')
    expect(wrapper.emitted('update:field')).toEqual([['name', 'Ada']])
  })

  it('renders field nodes', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'field', property: 'name', control: 'test-renderer-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: { name: 'Alice' }, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-field').exists()).toBe(true)
  })

  it('renders section nodes', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        {
          type: 'section',
          label: 'Info',
          children: [
            { type: 'field', property: 'name', control: 'test-renderer-ctrl' } as FieldNode,
          ],
        } as SectionNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-section').exists()).toBe(true)
  })

  it('renders row nodes', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        {
          type: 'row',
          children: [
            { type: 'field', property: 'name', control: 'test-renderer-ctrl' } as FieldNode,
            { type: 'field', property: 'email', control: 'test-renderer-ctrl' } as FieldNode,
          ],
        } as RowNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-row').exists()).toBe(true)
  })

  it('renders display nodes (alert)', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'alert', text: 'Important notice', variant: 'warning' } as DisplayNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.text()).toContain('Important notice')
  })

  it('renders display nodes (divider)', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'divider' } as DisplayNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('hr').exists()).toBe(true)
  })

  it('renders display nodes (heading)', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'heading', text: 'Title', level: 2 } as DisplayNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('h2').text()).toBe('Title')
  })

  it('renders display nodes (help-text)', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'help-text', text: 'Some help' } as DisplayNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('p').text()).toBe('Some help')
  })

  it('ignores unknown display control types', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'unknown-widget' } as DisplayNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: {}, readonly: false, errors: {} },
    })
    // Should render without error, just nothing for the unknown control
    expect(wrapper.find('.bosca-form-renderer').exists()).toBe(true)
  })

  it('renders a mixed layout with multiple node types', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'display', control: 'heading', text: 'Form', level: 1 } as DisplayNode,
        { type: 'display', control: 'divider' } as DisplayNode,
        { type: 'field', property: 'name', control: 'test-renderer-ctrl' } as FieldNode,
        {
          type: 'section',
          label: 'Contact',
          children: [
            { type: 'field', property: 'email', control: 'test-renderer-ctrl' } as FieldNode,
          ],
        } as SectionNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: { name: 'Bob' }, readonly: false, errors: {} },
    })
    expect(wrapper.find('h1').text()).toBe('Form')
    expect(wrapper.find('hr').exists()).toBe(true)
    expect(wrapper.findAll('.bosca-form-field').length).toBe(2)
    expect(wrapper.find('.bosca-form-section').exists()).toBe(true)
  })

  it('emits update:field from field children', () => {
    const uiSchema: UiSchema = {
      version: 1,
      layout: [
        { type: 'field', property: 'name', control: 'test-renderer-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(BoscaFormRenderer, {
      props: { schema, uiSchema, modelValue: { name: 'old' }, readonly: false, errors: {} },
    })
    const field = wrapper.findComponent({ name: 'FormField' })
    field.vm.$emit('update:field', 'name', 'new')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['name', 'new'])
  })
})
