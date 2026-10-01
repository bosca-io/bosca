import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import AttributesFile from './AttributesFile.vue'
import type { AttributeState } from '~/utils/editor/attribute'
import type { Uploader } from '~/utils/editor/uploader'

const mockGqlQuery = vi.fn().mockResolvedValue({ content: { metadata: null } })
vi.stubGlobal('useGraphQL', () => ({ query: mockGqlQuery, useSubscription: vi.fn() }))

const mockUploader = {
  upload: vi.fn().mockResolvedValue('uploaded-id'),
}

function makeAttribute(overrides: Record<string, unknown> = {}) {
  return {
    key: 'video.featured',
    metadata: null,
    configuration: {},
    changeRef: { value: '0' },
    addListener: vi.fn(),
    removeListener: vi.fn(),
    ...overrides,
  } as unknown as AttributeState
}

const stubs = {
  ClientOnly: { template: '<slot />' },
  Icon: { template: '<span class="icon" />', props: ['name', 'size', 'color'] },
  Badge: { template: '<span class="badge"><slot /></span>' },
  AttributesTitlebar: { template: '<div class="titlebar" />' },
  SMediaPlayer: { template: '<div class="mock-player" />', props: ['item'] },
  LazyTimeEventsEditor: { template: '<div class="mock-timeline-editor" />' },
  AttributesFileSelectorModal: {
    template: '<div class="mock-selector" />',
    props: ['onSelected', 'searchFilter'],
    emits: ['close'],
  },
}

function mountFile(attribute = makeAttribute(), editable = true) {
  return mount(AttributesFile, {
    props: {
      item: null,
      attribute,
      uploader: mockUploader as unknown as Uploader,
      editable,
      toolsEnabled: false,
      onRunTool: vi.fn(),
    },
    global: { stubs },
  })
}

describe('AttributesFile', () => {
  beforeEach(() => vi.clearAllMocks())

  it('opens the asset selector on dropzone click instead of a file dialog', async () => {
    const wrapper = mountFile()
    expect(wrapper.find('.mock-selector').exists()).toBe(false)
    await wrapper.find('.file-dropzone').trigger('click')
    expect(wrapper.find('.mock-selector').exists()).toBe(true)
  })

  it('does not render a native file input', () => {
    const wrapper = mountFile()
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('passes the attribute searchFilter to the selector', async () => {
    const attribute = makeAttribute({ configuration: { searchFilter: 'contentType STARTS WITH "video/"' } })
    const wrapper = mountFile(attribute)
    await wrapper.find('.file-dropzone').trigger('click')
    const selector = wrapper.findComponent('.mock-selector') as VueWrapper
    expect((selector.props() as Record<string, unknown>).searchFilter).toBe('contentType STARTS WITH "video/"')
  })

  it('sets the attribute metadata when an asset is selected', async () => {
    const attribute = makeAttribute({ configuration: { relationship: 'video.featured' } })
    const wrapper = mountFile(attribute)
    await wrapper.find('.file-dropzone').trigger('click')
    const selector = wrapper.findComponent('.mock-selector') as VueWrapper
    const onSelected = (selector.props() as Record<string, unknown>).onSelected as (
      _item: { id: string; name: string; contentType: string },
    ) => void
    onSelected({ id: 'asset-1', name: 'Intro Video', contentType: 'video/mp4' })
    expect((attribute as unknown as { metadata: unknown }).metadata).toEqual({
      id: 'asset-1',
      relationship: 'video.featured',
      contentType: 'video/mp4',
      attributes: { sort: 0 },
      name: 'Intro Video',
    })
  })

  it('still uploads files dropped onto the dropzone', async () => {
    const attribute = makeAttribute()
    const wrapper = mountFile(attribute)
    const file = new File(['x'], 'clip.mp4', { type: 'video/mp4' })
    await wrapper.find('.file-dropzone').trigger('drop', { dataTransfer: { files: [file] } })
    await flushPromises()
    expect(mockUploader.upload).toHaveBeenCalledWith(file, 'en-US')
    expect((attribute as unknown as { metadata: { id: string } }).metadata.id).toBe('uploaded-id')
  })

  it('does not render the dropzone when a file is already set', () => {
    const attribute = makeAttribute({
      metadata: { id: 'meta-1', name: 'clip.mp4', contentType: 'application/pdf', attributes: {} },
    })
    const wrapper = mountFile(attribute)
    expect(wrapper.find('.file-dropzone').exists()).toBe(false)
    expect(wrapper.find('.file-name').text()).toBe('clip.mp4')
  })
})
