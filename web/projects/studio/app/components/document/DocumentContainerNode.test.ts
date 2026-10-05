import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentContainerNode from './DocumentContainerNode.vue'
import { executeTool } from '~/utils/editor/tool'

vi.mock('@tiptap/vue-3', () => ({
  NodeViewWrapper: { template: '<div class="nv-wrapper"><slot /></div>' },
  NodeViewContent: { template: '<div class="nv-content" />' },
}))

vi.mock('~/utils/editor/tool', () => ({
  executeTool: vi.fn().mockResolvedValue(null),
}))

const PickerStub = {
  name: 'AttributesToolPickerModal',
  props: ['tools'],
  emits: ['select', 'close'],
  template: '<div class="tool-picker" />',
}

const stubs = {
  Icon: { template: '<i class="icon" :data-name="name" />', props: ['name', 'size', 'color'] },
  Popover: {
    template: '<span class="mock-popover"><slot name="trigger" /><slot /></span>',
    props: ['placement', 'trigger', 'interactive', 'delay'],
  },
  AttributesToolPickerModal: PickerStub,
  DocumentBibleReferences: { template: '<div class="bible-ref" />' },
  DocumentMetadataReference: { template: '<div class="metadata-ref" />' },
}

const SINGLE_TOOL = [
  { name: 'Generate', description: 'Generate the body', query: 'query {}', resultPath: 'a.b' },
]
const MULTI_TOOLS = [
  { name: 'Transcribe', description: 'Transcribe the audio', query: 'query {}', resultPath: 'a.b' },
  { name: 'Summarize', description: 'Summarize the transcript', query: 'query {}', resultPath: 'a.c' },
]

// eslint-disable-next-line @typescript-eslint/no-explicit-any
function makeProps(tools: typeof SINGLE_TOOL, editable = true): any {
  return {
    extension: {
      options: {
        containers: [{ id: 'c1', name: 'My Container', description: 'A container', type: 'TEXT', tools }],
        metadata: { id: 'm1' },
      },
    },
    editor: { isEditable: editable, on: vi.fn(), off: vi.fn() },
    getPos: () => 0,
    updateAttributes: vi.fn(),
    node: { attrs: { references: [], metadataId: undefined, renderer: undefined }, content: { size: 2 }, nodeSize: 4 },
    HTMLAttributes: { name: 'c1' },
  }
}

function mountNode(tools: typeof SINGLE_TOOL, editable = true) {
  return mount(DocumentContainerNode, { props: makeProps(tools, editable), global: { stubs } })
}

describe('DocumentContainerNode tools', () => {
  beforeEach(() => {
    vi.mocked(executeTool).mockClear()
    vi.mocked(executeTool).mockResolvedValue(null)
  })

  it('renders a single tool button with a sparkles icon', () => {
    const wrapper = mountNode(SINGLE_TOOL)
    const buttons = wrapper.findAll('.mock-popover .container-action')
    expect(buttons).toHaveLength(1)
    expect(wrapper.find('.mock-popover .container-action .icon').attributes('data-name')).toBe('sparkles')
  })

  it('renders exactly one tool button even when several tools exist', () => {
    const wrapper = mountNode(MULTI_TOOLS)
    expect(wrapper.findAll('.mock-popover')).toHaveLength(1)
    expect(wrapper.findAll('.mock-popover .container-action')).toHaveLength(1)
  })

  it('runs the tool directly on click when there is one tool', async () => {
    const wrapper = mountNode(SINGLE_TOOL)
    await wrapper.find('.mock-popover .container-action').trigger('click')
    await flushPromises()
    expect(executeTool).toHaveBeenCalledTimes(1)
    expect(executeTool).toHaveBeenCalledWith({ id: 'm1' }, SINGLE_TOOL[0], null, expect.anything())
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)
  })

  it('opens the picker instead of running a tool when multiple tools exist', async () => {
    const wrapper = mountNode(MULTI_TOOLS)
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)

    await wrapper.find('.mock-popover .container-action').trigger('click')

    expect(executeTool).not.toHaveBeenCalled()
    const picker = wrapper.findComponent(PickerStub)
    expect(picker.exists()).toBe(true)
    expect(picker.props('tools')).toEqual(MULTI_TOOLS)
  })

  it('runs the selected tool and closes the picker on select', async () => {
    const wrapper = mountNode(MULTI_TOOLS)
    await wrapper.find('.mock-popover .container-action').trigger('click')

    wrapper.findComponent(PickerStub).vm.$emit('select', MULTI_TOOLS[1])
    await flushPromises()

    expect(executeTool).toHaveBeenCalledTimes(1)
    expect(executeTool).toHaveBeenCalledWith({ id: 'm1' }, MULTI_TOOLS[1], null, expect.anything())
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)
  })

  it('closes the picker without running a tool when dismissed', async () => {
    const wrapper = mountNode(MULTI_TOOLS)
    await wrapper.find('.mock-popover .container-action').trigger('click')

    wrapper.findComponent(PickerStub).vm.$emit('close')
    await flushPromises()

    expect(executeTool).not.toHaveBeenCalled()
    expect(wrapper.findComponent(PickerStub).exists()).toBe(false)
  })

  it('shows the tool description and a run hint in the popover for a single tool', () => {
    const wrapper = mountNode(SINGLE_TOOL)
    expect(wrapper.find('.tool-popover-title').text()).toContain('Generate')
    expect(wrapper.find('.tool-popover-text').text()).toBe('Generate the body')
    expect(wrapper.find('.tool-popover-hint').text()).toBe('Click to run.')
  })

  it('lists every tool and a choose hint in the popover for multiple tools', () => {
    const wrapper = mountNode(MULTI_TOOLS)
    const names = wrapper.findAll('.tool-popover-name').map(n => n.text())
    expect(names).toEqual(['Transcribe', 'Summarize'])
    expect(wrapper.find('.tool-popover-hint').text()).toBe('Click to choose a tool.')
  })

  it('renders no tool button when the editor is not editable', () => {
    const wrapper = mountNode(SINGLE_TOOL, false)
    expect(wrapper.find('.mock-popover').exists()).toBe(false)
  })

  it('removes invalid Bible references before updating the document node', async () => {
    const props = makeProps(SINGLE_TOOL)
    props.extension.options.containers[0].type = 'BIBLE'
    props.node.attrs.references = ['MAT.6.24', null, '', 'MAT.6.33']

    mount(DocumentContainerNode, { props, global: { stubs } })
    await flushPromises()

    expect(props.updateAttributes).toHaveBeenCalledWith({
      references: ['MAT.6.24', 'MAT.6.33'],
      metadataId: undefined,
    })
  })
})
