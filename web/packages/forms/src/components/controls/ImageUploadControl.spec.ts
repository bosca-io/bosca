import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '../../test-helpers'
import ImageUploadControl from './ImageUploadControl.vue'
import type { FieldNode } from '../../types'
import { flushPromises } from '@vue/test-utils'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'avatar', control: 'image-upload', ...overrides }
}

let capturedOnload: ((e: any) => void) | null = null
const readAsDataURL = vi.fn()

function installFileReaderMock() {
  capturedOnload = null
  readAsDataURL.mockClear()
  vi.stubGlobal('FileReader', class {
    onload: ((e: any) => void) | null = null
    readAsDataURL(...args: any[]) {
      capturedOnload = this.onload
      readAsDataURL(...args)
    }
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('ImageUploadControl', () => {
  it('shows file input when not readonly', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
  })

  it('hides file input when readonly', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: true },
    })
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('shows image preview when value exists', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: 'data:image/png;base64,abc', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('img').exists()).toBe(true)
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,abc')
  })

  it('does not show preview when value is null', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('img').exists()).toBe(false)
  })

  it('shows remove button when value exists and not readonly', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: 'data:image/png;base64,abc', node: makeNode(), readonly: false },
    })
    expect(wrapper.find('button').exists()).toBe(true)
    expect(wrapper.find('button').text()).toBe('Remove')
  })

  it('hides remove button when readonly', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: 'data:image/png;base64,abc', node: makeNode(), readonly: true },
    })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('emits null on clear and resets preview', async () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: 'data:image/png;base64,abc', node: makeNode(), readonly: false },
    })
    await wrapper.find('button').trigger('click')
    expect(wrapper.emitted('update:value')![0]).toEqual([null])
  })

  it('respects custom accept attribute from node', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode({ accept: 'image/png' }), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').attributes('accept')).toBe('image/png')
  })

  it('defaults accept to image/*', () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').attributes('accept')).toBe('image/*')
  })

  it('reads file and emits data URL on file selection', async () => {
    installFileReaderMock()
    const mockResult = 'data:image/png;base64,MOCKDATA'

    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    const file = new File(['pixels'], 'photo.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')

    capturedOnload!({ target: { result: mockResult } })
    await flushPromises()

    expect(readAsDataURL).toHaveBeenCalledWith(file)
    expect(wrapper.emitted('update:value')![0]).toEqual([mockResult])
  })

  it('does nothing when no file is selected', async () => {
    const wrapper = mount(ImageUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [], configurable: true })
    await input.trigger('change')

    expect(wrapper.emitted('update:value')).toBeFalsy()
  })
})
