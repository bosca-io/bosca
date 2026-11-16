import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { ref } from 'vue'
import { print } from 'graphql'
import TextInput from '../../../../../packages/ui/src/components/TextInput.vue'
import Button from '../../../../../packages/ui/src/components/Button.vue'
import AttributesFileSelectorModal from './AttributesFileSelectorModal.vue'

const mockQuery = vi.fn()
const mockMutation = vi.fn()
const mockError = vi.fn()
const currentLanguage = ref<{ tag: string } | null>({ tag: 'es' })
vi.stubGlobal('useGraphQL', () => ({ query: mockQuery, mutation: mockMutation }))
vi.stubGlobal('useLanguage', () => ({ current: currentLanguage }))
vi.stubGlobal('useToast', () => ({ error: mockError }))

const youtubeAsset = {
  id: 'youtube-1',
  name: 'YouTube Video (dQw4w9WgXcQ)',
  content: { type: 'bosca/x-youtube-video' },
  attributes: { type: 'YouTube Video', 'youtube.id': 'dQw4w9WgXcQ' },
}

const youtubeResult = { content: { metadata: { add: youtubeAsset } } }

const stubs = {
  Modal: {
    template: '<div class="mock-modal"><slot /><slot name="footer" /></div>',
    props: ['title', 'subtitle', 'icon', 'accent', 'width'],
    emits: ['close'],
  },
  Icon: { template: '<span class="icon" />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span class="badge"><slot /></span>' },
  TextInput,
  Button,
}

function makeSearchResult(items: Array<{ id: string; name: string; type?: string; attributes?: Record<string, unknown> }>) {
  return {
    search: {
      search: {
        documents: items.map((i) => ({
          metadata: { id: i.id, name: i.name, attributes: i.attributes ?? null, content: i.type ? { type: i.type } : null },
        })),
        estimatedHits: items.length,
      },
    },
  }
}

describe('AttributesFileSelectorModal', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    currentLanguage.value = { tag: 'es' }
    mockMutation.mockReset().mockResolvedValue(youtubeResult)
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
    expect(onSelected).toHaveBeenCalledWith({ id: 'asset-1', name: 'Intro Video', contentType: 'video/mp4', attributes: {} })
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

  it('fetches and preserves attributes when selecting an existing YouTube asset', async () => {
    mockQuery.mockResolvedValue(makeSearchResult([{
      id: youtubeAsset.id, name: youtubeAsset.name, type: youtubeAsset.content.type, attributes: youtubeAsset.attributes,
    }]))
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, { props: { onSelected }, global: { stubs } })
    await flushPromises()
    expect(print(mockQuery.mock.calls[0]![0])).toContain('attributes')
    await wrapper.get('.asset-row').trigger('click')
    expect(onSelected).toHaveBeenCalledWith({
      id: youtubeAsset.id, name: youtubeAsset.name, contentType: youtubeAsset.content.type, attributes: youtubeAsset.attributes,
    })
    expect(wrapper.emitted('close')).toHaveLength(1)
  })

  it.each([undefined, false])('hides YouTube creation when allowYouTube is %s', (allowYouTube) => {
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn(), allowYouTube }, global: { stubs },
    })
    expect(wrapper.find('.youtube-form').exists()).toBe(false)
  })

  it.each([
    'dQw4w9WgXcQ',
    '  dQw4w9WgXcQ  ',
    'https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=20',
    'https://youtu.be/dQw4w9WgXcQ?si=shared',
    'https://www.youtube.com/embed/dQw4w9WgXcQ?start=20',
    'https://www.youtube.com/shorts/dQw4w9WgXcQ',
    'https://m.youtube.com/watch?v=dQw4w9WgXcQ',
    'youtube.com/watch?v=dQw4w9WgXcQ',
    'https://www.youtu.be/dQw4w9WgXcQ',
    'https://www.youtube.com/live/dQw4w9WgXcQ',
    'https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ',
  ])('creates and selects a ready YouTube asset from %s', async (input) => {
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected, allowYouTube: true }, global: { stubs },
    })
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue(input)
    await wrapper.get('.youtube-form').trigger('submit')
    await flushPromises()
    expect(mockMutation).toHaveBeenCalledExactlyOnceWith(expect.anything(), {
      metadata: {
        name: youtubeAsset.name,
        contentType: youtubeAsset.content.type,
        languageTag: 'es',
        attributes: youtubeAsset.attributes,
      },
      setReady: true,
    })
    const mutation = print(mockMutation.mock.calls[0]![0])
    expect(mutation).toContain('add(metadata: $metadata, setReady: $setReady)')
    expect(mutation).toContain('attributes')
    expect(onSelected).toHaveBeenCalledWith({
      id: youtubeAsset.id, name: youtubeAsset.name, contentType: youtubeAsset.content.type, attributes: youtubeAsset.attributes,
    })
    expect(wrapper.emitted('close')).toHaveLength(1)
    expect(mockError).not.toHaveBeenCalled()
  })

  it.each(['', 'not a video', 'https://www.youtube.com/watch', 'https://example.com/watch?v=dQw4w9WgXcQ'])('rejects invalid input: %s', async (input) => {
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected, allowYouTube: true }, global: { stubs },
    })
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue(input)
    await wrapper.get('.youtube-form').trigger('submit')
    expect(mockMutation).not.toHaveBeenCalled()
    expect(mockError).toHaveBeenCalledWith('Enter a valid YouTube URL or ID')
    expect(onSelected).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toBeUndefined()
  })

  it('uses English when no language is selected', async () => {
    currentLanguage.value = null
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected: vi.fn(), allowYouTube: true }, global: { stubs },
    })
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue('dQw4w9WgXcQ')
    await wrapper.get('.youtube-form').trigger('submit')
    await flushPromises()
    expect(mockMutation).toHaveBeenCalledWith(expect.anything(), expect.objectContaining({
      metadata: expect.objectContaining({ languageTag: 'en' }),
    }))
  })

  it('disables the form and prevents duplicate submissions while creating', async () => {
    let finish!: (_value: typeof youtubeResult) => void
    mockMutation.mockReturnValue(new Promise(resolve => { finish = resolve }))
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected, allowYouTube: true }, global: { stubs },
    })
    expect(wrapper.get('.youtube-form button').attributes('disabled')).toBeDefined()
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue('dQw4w9WgXcQ')
    await wrapper.get('.youtube-form').trigger('submit')
    expect(wrapper.get('.youtube-form button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('input[placeholder="YouTube URL or ID"]').attributes('disabled')).toBeDefined()
    await wrapper.get('.youtube-form').trigger('submit')
    expect(mockMutation).toHaveBeenCalledTimes(1)
    finish(youtubeResult)
    await flushPromises()
    expect(onSelected).toHaveBeenCalledTimes(1)
  })

  it('keeps the input and permits retry after an API failure', async () => {
    mockMutation.mockRejectedValueOnce(new Error('Permission denied'))
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected, allowYouTube: true }, global: { stubs },
    })
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue('dQw4w9WgXcQ')
    await wrapper.get('.youtube-form').trigger('submit')
    await flushPromises()
    expect(mockError).toHaveBeenCalledWith('Permission denied')
    expect(onSelected).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toBeUndefined()
    expect((wrapper.get('input[placeholder="YouTube URL or ID"]').element as HTMLInputElement).value).toBe('dQw4w9WgXcQ')
    expect(wrapper.get('.youtube-form button').attributes('disabled')).toBeUndefined()
    await wrapper.get('.youtube-form').trigger('submit')
    await flushPromises()
    expect(onSelected).toHaveBeenCalledTimes(1)
  })

  it('keeps the picker open if the mutation returns no asset', async () => {
    mockMutation.mockResolvedValue({ content: { metadata: { add: null } } })
    const onSelected = vi.fn()
    const wrapper = mount(AttributesFileSelectorModal, {
      props: { onSelected, allowYouTube: true }, global: { stubs },
    })
    await wrapper.get('input[placeholder="YouTube URL or ID"]').setValue('dQw4w9WgXcQ')
    await wrapper.get('.youtube-form').trigger('submit')
    await flushPromises()
    expect(mockError).toHaveBeenCalledWith('Unable to create YouTube metadata')
    expect(onSelected).not.toHaveBeenCalled()
    expect(wrapper.emitted('close')).toBeUndefined()
  })
})
