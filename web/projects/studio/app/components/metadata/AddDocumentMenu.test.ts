import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
// The real OverflowMenu: this test covers the menu integration (toggle slot,
// select event, v-model:open), not just the handler in isolation.
import OverflowMenu from '../../../../../packages/ui/src/components/OverflowMenu.vue'
import { getEditorRoute } from '../../utils/getEditorRoute'
import AddDocumentMenu from './AddDocumentMenu.vue'

const query = vi.fn()
const mutation = vi.fn()
const push = vi.fn()
const toastError = vi.fn()

vi.stubGlobal('useGraphQL', () => ({ query, mutation }))
vi.stubGlobal('useRouter', () => ({ push }))
vi.stubGlobal('useToast', () => ({ error: toastError }))
vi.stubGlobal('getEditorRoute', getEditorRoute)

const templatesResponse = {
  content: {
    collections: { root: { id: 'root-collection-id' } },
    documentTemplates: {
      all: [
        { metadata: { id: 'tpl-article', version: 3, name: 'Article', attributes: null } },
        { metadata: { id: 'tpl-sub', version: 1, name: 'Sub Template', attributes: { 'template.type.sub': 'x' } } },
        { metadata: null },
      ],
    },
  },
}

const stubs = {
  Icon: { template: '<i />' },
  Button: {
    template: '<button class="trigger" :disabled="disabled" @click="$emit(\'click\')"><slot /></button>',
    props: ['icon', 'size', 'accent', 'primary', 'disabled'],
    emits: ['click'],
  },
}

function mountMenu(props: Record<string, unknown> = {}) {
  return mount(AddDocumentMenu, {
    props,
    global: {
      stubs,
      components: { OverflowMenu },
    },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  query.mockResolvedValue(templatesResponse)
})

describe('AddDocumentMenu', () => {
  it('lists top-level templates, excluding sub-templates', async () => {
    const wrapper = mountMenu()
    await flushPromises()
    await wrapper.find('.trigger').trigger('click')
    const labels = wrapper.findAll('.overflow-item').map(b => b.text())
    expect(labels).toEqual(['Article'])
  })

  it('creates a document from the selected template and navigates to the editor', async () => {
    mutation.mockResolvedValue({ content: { metadata: { addDocument: { id: 'new-doc-id' } } } })
    const wrapper = mountMenu()
    await flushPromises()

    await wrapper.find('.trigger').trigger('click')
    await wrapper.find('.overflow-item').trigger('click')
    await flushPromises()

    expect(mutation).toHaveBeenCalledWith(expect.anything(), {
      parentCollectionId: 'root-collection-id',
      templateId: 'tpl-article',
      templateVersion: 3,
    })
    expect(push).toHaveBeenCalledWith('/cms/editor/new-doc-id')
    expect(toastError).not.toHaveBeenCalled()
  })

  it('opens via the open model for the command palette trigger', async () => {
    const wrapper = mountMenu({ open: true, 'onUpdate:open': () => {} })
    await flushPromises()
    expect(wrapper.findAll('.overflow-item').length).toBeGreaterThan(0)
  })

  it('shows an error toast and stays put when creation fails', async () => {
    mutation.mockRejectedValue(new Error('boom'))
    const wrapper = mountMenu()
    await flushPromises()

    await wrapper.find('.trigger').trigger('click')
    await wrapper.find('.overflow-item').trigger('click')
    await flushPromises()

    expect(toastError).toHaveBeenCalled()
    expect(push).not.toHaveBeenCalled()
  })
})
