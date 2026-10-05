import { describe, it, expect, vi } from 'vitest'
import { ref, nextTick } from 'vue'
import { mount } from '@vue/test-utils'
import AttributesToolButton from './AttributesToolButton.vue'
import type { AttributeState } from '~/utils/editor/attribute'

const PickerStub = {
  name: 'AttributesToolPickerModal',
  props: ['tools'],
  emits: ['select', 'close'],
  template: '<div class="tool-picker" />',
}

const stubs = {
  Icon: {
    template: '<i class="icon" :data-name="name" />',
    props: ['name', 'size', 'color'],
  },
  Popover: {
    template: '<span class="mock-popover"><slot name="trigger" /><slot /></span>',
    props: ['placement', 'trigger', 'interactive', 'delay'],
  },
  AttributesToolPickerModal: PickerStub,
}

function makeAttribute(loading = false) {
  return {
    loading: ref(loading),
    tools: [
      { name: 'Generate Description', description: 'Generate a description with AI', query: 'query {}', resultPath: 'a.b' },
    ],
  } as unknown as AttributeState
}

function makeMultiToolAttribute(loading = false) {
  return {
    loading: ref(loading),
    tools: [
      { name: 'Transcribe', description: 'Transcribe the audio', query: 'query {}', resultPath: 'a.b' },
      { name: 'Summarize', description: 'Summarize the transcript', query: 'query {}', resultPath: 'a.c' },
    ],
  } as unknown as AttributeState
}

function mountButton(attribute: AttributeState, onRunTool = vi.fn()) {
  const wrapper = mount(AttributesToolButton, {
    props: { attribute, editable: true, toolsEnabled: true, onRunTool },
    global: { stubs },
  })
  return { wrapper, onRunTool }
}

describe('AttributesToolButton', () => {
  it('renders a sparkles icon when idle', () => {
    const { wrapper } = mountButton(makeAttribute())
    expect(wrapper.find('.icon').attributes('data-name')).toBe('sparkles')
  })

  it('shows a spinning loader while the tool is running', () => {
    const { wrapper } = mountButton(makeAttribute(true))
    const icon = wrapper.find('.icon')
    expect(icon.attributes('data-name')).toBe('spinner')
    expect(icon.classes()).toContain('tool-spin')
  })

  it('disables the button while loading and ignores clicks', async () => {
    const { wrapper, onRunTool } = mountButton(makeAttribute(true))
    const button = wrapper.find('.tool-btn')
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    expect(onRunTool).not.toHaveBeenCalled()
  })

  it('runs the tool on click when idle', async () => {
    const attribute = makeAttribute()
    const { wrapper, onRunTool } = mountButton(attribute)
    await wrapper.find('.tool-btn').trigger('click')
    expect(onRunTool).toHaveBeenCalledWith(attribute, attribute.tools![0])
  })

  it('uses the tool description as the tooltip', () => {
    const { wrapper } = mountButton(makeAttribute())
    expect(wrapper.find('.tool-btn').attributes('title')).toBe('Generate a description with AI')
  })

  it('renders nothing when the attribute has no tools', () => {
    const attribute = { loading: ref(false), tools: [] } as unknown as AttributeState
    const { wrapper } = mountButton(attribute)
    expect(wrapper.find('.tool-btn').exists()).toBe(false)
  })

  it('renders nothing when not editable', () => {
    const wrapper = mount(AttributesToolButton, {
      props: { attribute: makeAttribute(), editable: false, toolsEnabled: true, onRunTool: vi.fn() },
      global: { stubs },
    })
    expect(wrapper.find('.tool-btn').exists()).toBe(false)
  })

  it('renders exactly one button even when several tools exist', () => {
    const { wrapper } = mountButton(makeMultiToolAttribute())
    expect(wrapper.findAll('.tool-btn')).toHaveLength(1)
  })

  it('opens the picker instead of running a tool when multiple tools exist', async () => {
    const attribute = makeMultiToolAttribute()
    const { wrapper, onRunTool } = mountButton(attribute)
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)

    await wrapper.find('.tool-btn').trigger('click')

    expect(onRunTool).not.toHaveBeenCalled()
    const picker = wrapper.findComponent(PickerStub)
    expect(picker.exists()).toBe(true)
    expect(picker.props('tools')).toEqual(attribute.tools)
  })

  it('runs the selected tool and closes the picker on select', async () => {
    const attribute = makeMultiToolAttribute()
    const { wrapper, onRunTool } = mountButton(attribute)
    await wrapper.find('.tool-btn').trigger('click')

    wrapper.findComponent(PickerStub).vm.$emit('select', attribute.tools![1])
    await nextTick()

    expect(onRunTool).toHaveBeenCalledWith(attribute, attribute.tools![1])
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)
  })

  it('closes the picker without running a tool when dismissed', async () => {
    const attribute = makeMultiToolAttribute()
    const { wrapper, onRunTool } = mountButton(attribute)
    await wrapper.find('.tool-btn').trigger('click')

    wrapper.findComponent(PickerStub).vm.$emit('close')
    await nextTick()

    expect(onRunTool).not.toHaveBeenCalled()
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)
  })

  it('uses a generic tooltip when multiple tools exist', () => {
    const { wrapper } = mountButton(makeMultiToolAttribute())
    expect(wrapper.find('.tool-btn').attributes('title')).toBe('Run a tool')
  })

  it('shows the tool description and a run hint in the popover for a single tool', () => {
    const { wrapper } = mountButton(makeAttribute())
    expect(wrapper.find('.tool-popover-title').text()).toContain('Generate Description')
    expect(wrapper.find('.tool-popover-text').text()).toBe('Generate a description with AI')
    expect(wrapper.find('.tool-popover-hint').text()).toBe('Click to run.')
  })

  it('lists every tool and a choose hint in the popover for multiple tools', () => {
    const { wrapper } = mountButton(makeMultiToolAttribute())
    const names = wrapper.findAll('.tool-popover-name').map(n => n.text())
    expect(names).toEqual(['Transcribe', 'Summarize'])
    expect(wrapper.find('.tool-popover-hint').text()).toBe('Click to choose a tool.')
  })

  it('explains in the popover when tools are disabled', () => {
    const wrapper = mount(AttributesToolButton, {
      props: { attribute: makeAttribute(), editable: true, toolsEnabled: false, onRunTool: vi.fn() },
      global: { stubs },
    })
    expect(wrapper.find('.tool-popover-hint').text()).toBe("Tools aren't available here.")
  })

  it('shows a running hint in the popover while a tool executes', () => {
    const { wrapper } = mountButton(makeAttribute(true))
    expect(wrapper.find('.tool-popover-hint').text()).toBe('Running…')
  })
})
