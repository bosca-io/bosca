import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import AttributesImageEditor from './AttributesImageEditor.vue'
import type { AttributeState } from '~/utils/editor/attribute'

let _lastCropperInstance: MockCropper | null = null  
const cropperEventHandlers: Record<string, () => void> = {}

class MockCropper {
  on = vi.fn()
  zoom = vi.fn()
  setCoordinates = vi.fn()
  getResult = vi.fn(() => ({
    coordinates: { width: 100, height: 100, left: 0, top: 0 },
    visibleArea: { width: 200, height: 150, left: 0, top: 0 },
  }))

  constructor() {
    // eslint-disable-next-line @typescript-eslint/no-this-alias
    _lastCropperInstance = this
  }
}

vi.mock('vue-advanced-cropper', () => ({
  Cropper: {
    name: 'Cropper',
    props: ['src', 'defaultPosition', 'defaultTransforms', 'resizeImage', 'stencilProps'],
    emits: ['change', 'ready'],
     
    setup(_props: Record<string, unknown>, { emit, expose }: { emit: (event: string, ...args: unknown[]) => void; expose: (exposed: Record<string, unknown>) => void }) {
      const instance = new MockCropper()
      cropperEventHandlers.change = () => emit('change')
      cropperEventHandlers.ready = () => emit('ready')

      expose({
        zoom: instance.zoom,
        setCoordinates: instance.setCoordinates,
        getResult: instance.getResult,
      })

      return { instance }
    },
    template: '<div class="mock-cropper"><slot /></div>',
  },
}))

vi.mock('vue-advanced-cropper/dist/style.css', () => ({}))

function makeAttribute(overrides: Record<string, unknown> = {}) {
  const listeners: Array<() => void> = []
  return {
    key: 'image.featured',
    metadata: {
      id: 'img-1',
      name: 'test-image',
      contentType: 'image/jpeg',
      relationship: 'image',
      attributes: {},
      ...(overrides.metadata as Record<string, unknown> | undefined),
    },
    configuration: {
      relationship: 'image',
      ...(overrides.configuration as Record<string, unknown> | undefined),
    },
    changeRef: { value: '0' },
    addListener: vi.fn((fn: () => void) => listeners.push(fn)),
    removeListener: vi.fn((fn: () => void) => {
      const idx = listeners.indexOf(fn)
      if (idx >= 0) listeners.splice(idx, 1)
    }),
    _listeners: listeners,
    ...overrides,
  } as unknown as AttributeState
}

const stubs = {
  ClientOnly: { template: '<slot />' },
  Icon: { template: '<span />', props: ['name', 'size', 'color'] },
}

describe('AttributesImageEditor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    _lastCropperInstance = null
    Object.keys(cropperEventHandlers).forEach((k) => Reflect.deleteProperty(cropperEventHandlers, k))
  })

  it('renders Cropper with correct image source URL', () => {
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: makeAttribute(),
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.exists()).toBe(true)
    expect(cropper.props('src')).toBe('/content/image/img-1')
  })

  it('passes aspectRatio to stencil props', () => {
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: makeAttribute(),
        aspectRatio: 1.5,
        editable: true,
      },
      global: { stubs },
    })

    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.props('stencilProps')).toEqual({ aspectRatio: 1.5 })
  })

  it('passes null aspectRatio for free crop', () => {
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: makeAttribute(),
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.props('stencilProps')).toEqual({ aspectRatio: null })
  })

  it('restores saved crop from metadata attributes', () => {
    const savedCrop = { width: 50, height: 50, left: 10, top: 10 }
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: makeAttribute({
          metadata: { id: 'img-1', attributes: { crop: savedCrop } },
        }),
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.props('defaultPosition')).toEqual(savedCrop)
    expect(cropper.props('defaultTransforms')).toEqual(savedCrop)
  })

  it('handles null metadata gracefully', () => {
    const attr = makeAttribute()
    attr.metadata = null

    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: attr,
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    // When metadata is null, imageUrl is '' and v-if="imageUrl" prevents Cropper from rendering
    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.exists()).toBe(false)
  })

  it('registers listener on mount and removes on unmount', () => {
    const attr = makeAttribute()
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: attr,
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    expect(attr.addListener).toHaveBeenCalled()

    wrapper.unmount()

    expect(attr.removeListener).toHaveBeenCalled()
  })

  it('disables wheel zoom on the cropper', () => {
    const wrapper = mount(AttributesImageEditor, {
      props: {
        attribute: makeAttribute(),
        aspectRatio: null,
        editable: true,
      },
      global: { stubs },
    })

    const cropper = wrapper.findComponent({ name: 'Cropper' })
    expect(cropper.props('resizeImage')).toEqual({ wheel: false })
  })
})
