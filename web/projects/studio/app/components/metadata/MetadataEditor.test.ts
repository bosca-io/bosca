import { describe, it, expect, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import type * as Y from 'yjs'
import type { Collection, CollectionWorkflow } from '~/types/graphql'
import type { AttributeState } from '~/utils/editor/attribute'
import type { Uploader } from '~/utils/editor/uploader'
import MetadataEditor from './MetadataEditor.vue'

vi.mock('~/utils/editor/attributes', () => ({ applyAttributes: vi.fn() }))
vi.mock('~/utils/editor/tool', () => ({ executeTool: vi.fn() }))
vi.mock('json-editor-vue', () => ({
  default: { name: 'JsonEditorVue', template: '<div class="mock-json" />', props: ['modelValue'] },
}))

const fakeYdoc = {
  getText: () => ({ getAttribute: () => 'false', observe: () => {}, unobserve: () => {} }),
} as unknown as Y.Doc
const draftCollection = {
  __typename: 'Collection', id: 'col-1', workflow: { state: 'draft' },
} as unknown as Collection

const stubs = {
  AttributesSidebar: {
    template: '<div class="mock-sidebar" />',
    props: ['content', 'ydoc', 'editable', 'attributes', 'state', 'uploader', 'toolsEnabled', 'onRunTool', 'showSlug', 'emptyText'],
  },
  Switch: {
    template: '<button class="mock-switch" @click="$emit(\'update:modelValue\', !modelValue)" />',
    props: ['modelValue', 'label'],
  },
}

function mountEditor(extraProps: { state?: CollectionWorkflow | null; emptyText?: string } = {}) {
  return mount(MetadataEditor, {
    props: {
      metadata: draftCollection,
      ydoc: fakeYdoc,
      attributes: new Map<string, AttributeState>(),
      uploader: {} as Uploader,
      rawAttributes: {},
      ...extraProps,
    },
    global: { stubs },
  })
}

function sidebarProps(wrapper: VueWrapper) {
  return (wrapper.findComponent('.mock-sidebar') as VueWrapper).props() as Record<string, unknown>
}

describe('MetadataEditor', () => {
  it("shows the item's own workflow in the sidebar status row by default", () => {
    const wrapper = mountEditor()
    expect(sidebarProps(wrapper).state).toEqual({ state: 'draft' })
  })

  it('prefers an explicit state, so a language variant can show its own workflow', () => {
    const variantWorkflow = { state: 'published', pending: 'review' } as unknown as CollectionWorkflow
    const wrapper = mountEditor({ state: variantWorkflow })
    expect(sidebarProps(wrapper).state).toEqual(variantWorkflow)
  })

  it('hides the slug in the sidebar (pages render their own slug field)', () => {
    const wrapper = mountEditor()
    expect(sidebarProps(wrapper).showSlug).toBe(false)
  })

  it('forwards the empty-state message to the sidebar', () => {
    const wrapper = mountEditor({ emptyText: 'Nothing to edit here.' })
    expect(sidebarProps(wrapper).emptyText).toBe('Nothing to edit here.')
  })

  it('swaps the sidebar for the raw JSON editor while Raw Attributes is on', async () => {
    const wrapper = mountEditor()
    expect(wrapper.find('.mock-sidebar').exists()).toBe(true)
    expect(wrapper.find('.mock-json').exists()).toBe(false)

    await wrapper.find('.mock-switch').trigger('click')
    await nextTick()
    expect(wrapper.find('.mock-sidebar').exists()).toBe(false)
    expect(wrapper.find('.mock-json').exists()).toBe(true)

    await wrapper.find('.mock-switch').trigger('click')
    await nextTick()
    expect(wrapper.find('.mock-sidebar').exists()).toBe(true)
    expect(wrapper.find('.mock-json').exists()).toBe(false)
  })
})
