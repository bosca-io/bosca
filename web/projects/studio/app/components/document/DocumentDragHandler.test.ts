import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DocumentDragHandler from './DocumentDragHandler.vue'
import type { Editor } from '@tiptap/vue-3'

vi.mock('@tiptap/extension-drag-handle-vue-3', () => ({
  DragHandle: { template: '<div class="drag-handle-host"><slot /></div>', props: ['editor', 'tippyOptions'] },
}))

const stubs = {
  Icon: { template: '<i />' },
}

function mountHandler(props: { editable: boolean }) {
  return mount(DocumentDragHandler, {
    props: { editor: { isEditable: false } as unknown as Editor, ...props },
    global: { stubs },
  })
}

describe('DocumentDragHandler', () => {
  it('shows the handle when the editable prop is true', () => {
    const wrapper = mountHandler({ editable: true })
    expect(wrapper.find('.drag-handle').exists()).toBe(true)
  })

  it('hides the handle when the editable prop is false', () => {
    const wrapper = mountHandler({ editable: false })
    expect(wrapper.find('.drag-handle').exists()).toBe(false)
  })

  it('reacts when the editable prop flips after mount', async () => {
    const wrapper = mountHandler({ editable: false })
    expect(wrapper.find('.drag-handle').exists()).toBe(false)
    await wrapper.setProps({ editable: true })
    expect(wrapper.find('.drag-handle').exists()).toBe(true)
  })
})
