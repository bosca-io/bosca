import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AttributesImage from './AttributesImage.vue'
import type { AttributeState } from '~/utils/editor/attribute'
import type { Uploader } from '~/utils/editor/uploader'

vi.mock('vue-advanced-cropper', () => ({
  Cropper: { template: '<div />' },
}))
vi.mock('vue-advanced-cropper/dist/style.css', () => ({}))

// Mock Nuxt auto-imports used by the component
vi.stubGlobal('useGraphQL', () => ({
  query: vi.fn(),
  useSubscription: vi.fn(),
}))
vi.stubGlobal('useLanguage', () => ({
  current: { value: { tag: 'en' } },
}))
vi.stubGlobal('useToast', () => ({
  success: vi.fn(),
  error: vi.fn(),
  showProgress: vi.fn(() => ({ update: vi.fn(), complete: vi.fn(), dismiss: vi.fn() })),
}))

const mockUploader = {
  upload: vi.fn().mockResolvedValue('uploaded-id'),
}

function makeAttribute(overrides: Record<string, unknown> = {}) {
  return {
    key: 'image.featured',
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
  AttributesTitlebar: { template: '<div class="titlebar" />' },
  AttributesImageEditor: {
    template: '<div class="mock-editor" />',
    props: ['attribute', 'aspectRatio', 'editable'],
    setup() {
      return {}
    },
  },
  AttributesImageSelectorModal: { template: '<div />' },
}

describe('AttributesImage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows dropzone when no image is set', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute(),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    expect(wrapper.find('.image-dropzone').exists()).toBe(true)
    expect(wrapper.find('.mock-editor').exists()).toBe(false)
  })

  it('shows editor when image metadata exists (editable)', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: { id: 'img-1', name: 'test.jpg', contentType: 'image/jpeg', attributes: {} },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    expect(wrapper.find('.mock-editor').exists()).toBe(true)
    expect(wrapper.find('.image-dropzone').exists()).toBe(false)
  })

  it('shows image preview in read-only mode', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: {
            id: 'img-1',
            name: 'test.jpg',
            contentType: 'image/jpeg',
            attributes: { jpeg: { large: 'hash123' } },
          },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: false,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    expect(wrapper.find('.image-preview').exists()).toBe(true)
    const img = wrapper.find('.image-preview-img')
    expect(img.attributes('src')).toBe('/content/image/img-1?key=hash123')
  })

  it('shows "No image" in read-only mode without image', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute(),
        uploader: mockUploader as unknown as Uploader,
        editable: false,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    expect(wrapper.find('.attr-empty').text()).toBe('No image')
  })

  it('uses correct image URL with jpeg.large key', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: {
            id: 'img-1',
            attributes: { jpeg: { large: 'abc123' } },
          },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: false,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const img = wrapper.find('.image-preview-img')
    expect(img.attributes('src')).toBe('/content/image/img-1?key=abc123')
  })

  it('falls back to URL without key when jpeg.large is missing', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: { id: 'img-1', attributes: {} },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: false,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const img = wrapper.find('.image-preview-img')
    expect(img.attributes('src')).toBe('/content/image/img-1')
  })

  it('clears image on clear button click', async () => {
    const attr = makeAttribute({
      metadata: { id: 'img-1', name: 'test.jpg', contentType: 'image/jpeg', attributes: {} },
    })

    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: attr,
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const clearBtn = wrapper.find('.toolbar-btn--danger')
    expect(clearBtn.exists()).toBe(true)
    await clearBtn.trigger('click')
    expect(attr.metadata).toBeNull()
  })

  it('renders aspect ratio buttons from configuration', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: { id: 'img-1', attributes: {} },
          configuration: {
            aspectRatios: [
              { name: '16:9', value: 16 / 9, icon: 'crop' },
              { name: '4:3', value: 4 / 3, icon: 'crop' },
            ],
          },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const toolbarBtns = wrapper.findAll('.toolbar-btn')
    const ratioBtn = toolbarBtns.find((b) => b.attributes('title') === '16:9')
    expect(ratioBtn).toBeDefined()
    const ratioBtn2 = toolbarBtns.find((b) => b.attributes('title') === '4:3')
    expect(ratioBtn2).toBeDefined()
  })

  it('hides aspect ratios when only one default ratio exists', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: { id: 'img-1', attributes: {} },
          configuration: {
            aspectRatios: [{ name: '16:9', value: 16 / 9, icon: 'crop', default: true }],
          },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const ratioBtn = wrapper.findAll('.toolbar-btn').find((b) => b.attributes('title') === '16:9')
    expect(ratioBtn).toBeUndefined()
  })

  it('renders action buttons from configuration', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute({
          metadata: { id: 'img-1', attributes: {} },
          configuration: {
            actions: [
              { name: 'Zoom In', action: 'zoomin', icon: 'zoomIn' },
              { name: 'Download', action: 'download', icon: 'download' },
            ],
          },
        }),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    const toolbarBtns = wrapper.findAll('.toolbar-btn')
    expect(toolbarBtns.find((b) => b.attributes('title') === 'Zoom In')).toBeDefined()
    expect(toolbarBtns.find((b) => b.attributes('title') === 'Download')).toBeDefined()
  })

  it('renders titlebar component', () => {
    const wrapper = mount(AttributesImage, {
      props: {
        item: null,
        attribute: makeAttribute(),
        uploader: mockUploader as unknown as Uploader,
        editable: true,
        toolsEnabled: false,
        onRunTool: vi.fn(),
      },
      global: { stubs },
    })

    expect(wrapper.find('.titlebar').exists()).toBe(true)
  })
})
