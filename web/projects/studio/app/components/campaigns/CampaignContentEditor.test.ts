import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import CampaignContentEditor from './CampaignContentEditor.vue'

const stubs = {
  Icon: { template: '<span />', props: ['name', 'size'] },
  CampaignEmailContentEditor: {
    name: 'CampaignEmailContentEditor',
    template: '<div class="mock-email-template-editor"><span class="field-label">BML Message Project</span><span class="field-label">BML Message Template</span></div>',
    props: ['modelValue', 'disabled'],
  },
}

describe('CampaignContentEditor', () => {
  it('renders push fields when channel is PUSH', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'PUSH' },
      global: { stubs },
    })
    const labels = wrapper.findAll('.field-label').map((l) => l.text())
    expect(labels).toContain('Title')
    expect(labels).toContain('Body')
    expect(labels).toContain('Default Action Label')
    expect(labels).toContain('Default Action URL')
    expect(labels).not.toContain('Subject')
    expect(labels).not.toContain('Image Position')
  })

  it('renders email fields when channel is EMAIL', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'EMAIL' },
      global: { stubs },
    })
    const labels = wrapper.findAll('.field-label').map((l) => l.text())
    expect(labels).toContain('BML Message Project')
    expect(labels).toContain('BML Message Template')
    expect(labels).not.toContain('Subject')
    expect(labels).not.toContain('HTML Body')
    expect(labels).not.toContain('Title')
  })

  it('renders banner fields when channel is BANNER', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'BANNER' },
      global: { stubs },
    })
    const labels = wrapper.findAll('.field-label').map((l) => l.text())
    expect(labels).toContain('Title')
    expect(labels).toContain('Body')
    expect(labels).toContain('Image Position')
    expect(labels).not.toContain('Subject')
  })

  it('forwards BML email content updates', async () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'EMAIL' },
      global: { stubs },
    })
    const content = { project: 'product-emails', templateKey: 'newsletter', payload: { title: 'Hello' } }
    wrapper.findComponent({ name: 'CampaignEmailContentEditor' }).vm.$emit('update:modelValue', content)
    await wrapper.vm.$nextTick()
    expect(wrapper.emitted('update:modelValue')).toBeTruthy()
    const emitted = wrapper.emitted('update:modelValue')!
    const lastValue = emitted[emitted.length - 1]![0] as Record<string, unknown>
    expect(lastValue).toEqual(content)
  })

  it('passes existing values to the BML email editor', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: {
        modelValue: { project: 'product-emails', templateKey: 'newsletter' },
        channel: 'EMAIL',
      },
      global: { stubs },
    })
    expect(wrapper.findComponent({ name: 'CampaignEmailContentEditor' }).props('modelValue')).toEqual({
      project: 'product-emails',
      templateKey: 'newsletter',
    })
  })

  it('disables the BML email editor when disabled prop is true', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'EMAIL', disabled: true },
      global: { stubs },
    })
    expect(wrapper.findComponent({ name: 'CampaignEmailContentEditor' }).props('disabled')).toBe(true)
  })

  it('renders push custom data section', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: {
        modelValue: { data: { foo: 'bar' } },
        channel: 'PUSH',
      },
      global: { stubs },
    })
    expect(wrapper.find('.custom-data').exists()).toBe(true)
    const rows = wrapper.findAll('.custom-data-row')
    expect(rows.length).toBe(1)
  })

  it('adds custom data entry', async () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'PUSH' },
      global: { stubs },
    })
    await wrapper.find('.section-add').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')!
    const lastValue = emitted[emitted.length - 1]![0] as Record<string, unknown>
    expect(lastValue.data).toBeDefined()
    expect(Object.keys(lastValue.data as Record<string, unknown>).length).toBe(1)
  })

  it('removes custom data entry', async () => {
    const wrapper = mount(CampaignContentEditor, {
      props: {
        modelValue: { data: { myKey: 'myVal' } },
        channel: 'PUSH',
      },
      global: { stubs },
    })
    await wrapper.find('.custom-data-remove').trigger('click')
    const emitted = wrapper.emitted('update:modelValue')!
    const lastValue = emitted[emitted.length - 1]![0] as Record<string, unknown>
    expect(Object.keys(lastValue.data as Record<string, unknown>).length).toBe(0)
  })

  it('renders banner compact checkbox', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'BANNER' },
      global: { stubs },
    })
    const checkLabels = wrapper.findAll('.field--check .field-label').map((l) => l.text())
    expect(checkLabels).toContain('Compact Layout')
  })

  it('renders push collapsible sections', () => {
    const wrapper = mount(CampaignContentEditor, {
      props: { modelValue: {}, channel: 'PUSH' },
      global: { stubs },
    })
    const toggles = wrapper.findAll('.section-toggle').map((t) => t.text())
    expect(toggles).toContain('Delivery Options')
    expect(toggles).toContain('Android Settings')
    expect(toggles).toContain('iOS Settings')
  })
})
