import { describe, it, expect, beforeEach } from 'vitest'
import { mount } from '../../test-helpers'
import { registerControl } from '../../controls'
import FormSection from './FormSection.vue'
import { defineComponent } from 'vue'
import type { SectionNode, JsonSchema, FieldNode, RowNode, DisplayNode } from '../../types'

const StubControl = defineComponent({
  props: ['value', 'node', 'propertySchema', 'readonly', 'error'],
  emits: ['update:value'],
  template: '<div class="stub">{{ value }}</div>',
})

const schema: JsonSchema = {
  type: 'object',
  properties: {
    name: { type: 'string' },
    email: { type: 'string' },
    phone: { type: 'string' },
  },
}

beforeEach(() => {
  registerControl('test-section-ctrl', StubControl)
})

describe('FormSection', () => {
  it('renders section label when provided', () => {
    const node: SectionNode = {
      type: 'section',
      label: 'Personal Info',
      children: [],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('legend').text()).toBe('Personal Info')
  })

  it('does not render label when not provided', () => {
    const node: SectionNode = {
      type: 'section',
      children: [],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('legend').exists()).toBe(false)
  })

  it('renders field children', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'field', property: 'name', control: 'test-section-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: { name: 'Test' }, readonly: false, errors: {} },
    })
    expect(wrapper.findAll('.bosca-form-field').length).toBe(1)
  })

  it('renders row children', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        {
          type: 'row',
          children: [
            { type: 'field', property: 'name', control: 'test-section-ctrl' } as FieldNode,
          ],
        } as RowNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('.bosca-form-row').exists()).toBe(true)
  })

  it('renders display children (divider)', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'display', control: 'divider' } as DisplayNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('hr').exists()).toBe(true)
  })

  it('renders display children (alert)', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'display', control: 'alert', text: 'Warning!', variant: 'warning' } as DisplayNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.text()).toContain('Warning!')
  })

  it('renders display children (heading)', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'display', control: 'heading', text: 'Sub-heading', level: 4 } as DisplayNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('h4').text()).toBe('Sub-heading')
  })

  it('renders display children (help-text)', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'display', control: 'help-text', text: 'Please fill out' } as DisplayNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('p').text()).toBe('Please fill out')
  })

  it('renders recursive nested sections', () => {
    const node: SectionNode = {
      type: 'section',
      label: 'Outer',
      children: [
        {
          type: 'section',
          label: 'Inner',
          children: [
            { type: 'field', property: 'name', control: 'test-section-ctrl' } as FieldNode,
          ],
        } as SectionNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: { name: 'Nested' }, readonly: false, errors: {} },
    })
    const sections = wrapper.findAll('.bosca-form-section')
    expect(sections.length).toBe(2)
    const legends = wrapper.findAll('legend')
    expect(legends[0].text()).toBe('Outer')
    expect(legends[1].text()).toBe('Inner')
    expect(wrapper.find('.bosca-form-field').exists()).toBe(true)
  })

  it('propagates update:field from nested section children', () => {
    const node: SectionNode = {
      type: 'section',
      label: 'Outer',
      children: [
        {
          type: 'section',
          label: 'Inner',
          children: [
            { type: 'field', property: 'email', control: 'test-section-ctrl' } as FieldNode,
          ],
        } as SectionNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: { email: 'old@test.com' }, readonly: false, errors: {} },
    })

    // Find the innermost FormField and emit
    const fields = wrapper.findAllComponents({ name: 'FormField' })
    expect(fields.length).toBe(1)
    fields[0].vm.$emit('update:field', 'email', 'new@test.com')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['email', 'new@test.com'])
  })

  it('propagates update:field from row children', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        {
          type: 'row',
          children: [
            { type: 'field', property: 'phone', control: 'test-section-ctrl' } as FieldNode,
          ],
        } as RowNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: { phone: '555' }, readonly: false, errors: {} },
    })

    const row = wrapper.findComponent({ name: 'FormRow' })
    row.vm.$emit('update:field', 'phone', '999')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['phone', '999'])
  })

  it('emits update:field from direct field children', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'field', property: 'name', control: 'test-section-ctrl' } as FieldNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: { name: 'old' }, readonly: false, errors: {} },
    })
    const field = wrapper.findComponent({ name: 'FormField' })
    field.vm.$emit('update:field', 'name', 'new')

    expect(wrapper.emitted('update:field')).toBeTruthy()
    expect(wrapper.emitted('update:field')![0]).toEqual(['name', 'new'])
  })

  it('renders mixed children types in order', () => {
    const node: SectionNode = {
      type: 'section',
      label: 'Mixed',
      children: [
        { type: 'display', control: 'heading', text: 'Header', level: 2 } as DisplayNode,
        { type: 'display', control: 'divider' } as DisplayNode,
        { type: 'field', property: 'name', control: 'test-section-ctrl' } as FieldNode,
        {
          type: 'row',
          children: [
            { type: 'field', property: 'email', control: 'test-section-ctrl' } as FieldNode,
            { type: 'field', property: 'phone', control: 'test-section-ctrl' } as FieldNode,
          ],
        } as RowNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    expect(wrapper.find('h2').text()).toBe('Header')
    expect(wrapper.find('hr').exists()).toBe(true)
    expect(wrapper.findAll('.bosca-form-field').length).toBe(3)
    expect(wrapper.find('.bosca-form-row').exists()).toBe(true)
  })

  it('ignores unknown display control types', () => {
    const node: SectionNode = {
      type: 'section',
      children: [
        { type: 'display', control: 'nonexistent-widget' } as DisplayNode,
      ],
    }
    const wrapper = mount(FormSection, {
      props: { node, schema, modelValue: {}, readonly: false, errors: {} },
    })
    // Should render without error, just the section wrapper
    expect(wrapper.find('.bosca-form-section').exists()).toBe(true)
  })
})
