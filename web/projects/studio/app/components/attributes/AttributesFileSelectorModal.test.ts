import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import AttributesFileSelectorModal from './AttributesFileSelectorModal.vue'

const mockQuery = vi.fn()
vi.stubGlobal('useGraphQL', () => ({ query: mockQuery }))

const stubs = {
  Modal: {
    template: '<div class="mock-modal"><slot /><slot name="footer" /></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Icon: { template: '<span class="icon" />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span class="badge"><slot /></span>' },
  TextInput: {
    template: '<input class="mock-search" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)">',
    props: ['modelValue', 'placeholder', 'icon'],
    emits: ['update:modelValue'],
  },
}

function makeSearchResult(items: Array<{ id: string; name: string; type?: string }>) {
  return {
    search: {
      search: {
        documents: items.map((i) => ({
          metadata: { id: i.id, name: i.name, slug: i.id, content: i.type ? { type: i.type } : null },
        })),
        estimatedHits: items.length,
      },
    },
  }
}

describe('AttributesFileSelectorModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockQuery.mockResolvedValue(makeSearchResult([
      { id: 'asset-1', name: 'Intro Video', type: 'video/mp4' },
      { id: 'asset-2', name: 'Theme Song', type: 'audio/mpeg' },
    ]))
  })

  it('searches on mount and renders asset rows', async () => {
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn() },
      global: { stubs },
    })
    await flushPromises()
    const rows = wrapper.findAll('.asset-row')
    expect(rows.length).toBe(2)
    expect(rows[0]!.text()).toContain('Intro Video')
    expect(rows[0]!.text()).toContain('video/mp4')
  })

  it('applies the base metadata filter when no searchFilter is provided', async () => {
    mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn() },
      global: { stubs },
    })
    await flushPromises()
    expect(mockQuery).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ filter: '_type = "metadata"' }),
    )
  })

  it('appends the attribute searchFilter to the query filter', async () => {
    mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn(), searchFilter: 'contentType STARTS WITH "video/"' },
      global: { stubs },
    })
    await flushPromises()
    expect(mockQuery).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ filter: '_type = "metadata" AND contentType STARTS WITH "video/"' }),
    )
  })

  it('invokes onSelected with the asset and closes on row click', async () => {
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected },
      global: { stubs },
    })
    await flushPromises()
    await wrapper.find('.asset-row').trigger('click')
    expect(onSelected).toHaveBeenCalledWith({ id: 'asset-1', name: 'Intro Video', contentType: 'video/mp4' })
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('shows the empty state when nothing matches', async () => {
    mockQuery.mockResolvedValue(makeSearchResult([]))
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn() },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.find('.selector-empty').text()).toContain('No assets available')
  })

  it('recovers to an empty list when the search fails', async () => {
    mockQuery.mockRejectedValue(new Error('boom'))
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn() },
      global: { stubs },
    })
    await flushPromises()
    expect(wrapper.findAll('.asset-row').length).toBe(0)
    expect(wrapper.find('.selector-empty').exists()).toBe(true)
  })
})
