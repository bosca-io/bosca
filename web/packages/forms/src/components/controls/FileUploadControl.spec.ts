import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '../../test-helpers'
import FileUploadControl from './FileUploadControl.vue'
import type { FieldNode } from '../../types'
import { flushPromises } from '@vue/test-utils'

function makeNode(overrides: Partial<FieldNode> = {}): FieldNode {
  return { type: 'field', property: 'attachment', control: 'file-upload', ...overrides }
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

describe('FileUploadControl', () => {
  it('keeps a selected file visible after the form becomes readonly and removes editing controls', async () => {
    installFileReaderMock()
    const wrapper = mount(FileUploadControl, { props: { value: null, node: makeNode(), readonly: false } })
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['content'], 'saved.txt')], configurable: true })
    await input.trigger('change')
    await wrapper.setProps({ readonly: true })
    expect(wrapper.text()).toContain('saved.txt')
    expect(wrapper.find('button').exists()).toBe(false)
    expect(wrapper.find('input').exists()).toBe(false)
  })

  it('shows file input when not readonly', () => {
    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').exists()).toBe(true)
  })

  it('hides file input when readonly', () => {
    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: true },
    })
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('defaults accept to *', () => {
    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').attributes('accept')).toBe('*')
  })

  it('uses custom accept from node', () => {
    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode({ accept: '.pdf,.doc' }), readonly: false },
    })
    expect(wrapper.find('input[type="file"]').attributes('accept')).toBe('.pdf,.doc')
  })

  it('reads file and emits data URL on file selection', async () => {
    installFileReaderMock()
    const mockResult = 'data:application/pdf;base64,MOCKDATA'

    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    const file = new File(['content'], 'doc.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')

    capturedOnload!({ target: { result: mockResult } })
    await flushPromises()

    expect(readAsDataURL).toHaveBeenCalledWith(file)
    expect(wrapper.emitted('update:value')![0]).toEqual([mockResult])
  })

  it('shows file name after selection', async () => {
    installFileReaderMock()
    const mockResult = 'data:application/pdf;base64,X'

    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    const file = new File(['content'], 'report.pdf', { type: 'application/pdf' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')

    capturedOnload!({ target: { result: mockResult } })
    await flushPromises()

    expect(wrapper.text()).toContain('report.pdf')
  })

  it('emits null and clears file name on remove', async () => {
    installFileReaderMock()

    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    const file = new File(['content'], 'test.txt', { type: 'text/plain' })
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    capturedOnload!({ target: { result: 'data:text/plain;base64,X' } })
    await flushPromises()

    expect(wrapper.text()).toContain('test.txt')

    await wrapper.find('button').trigger('click')

    const events = wrapper.emitted('update:value')!
    expect(events[events.length - 1]).toEqual([null])
  })

  it('does nothing when no file is selected', async () => {
    const wrapper = mount(FileUploadControl, {
      props: { value: null, node: makeNode(), readonly: false },
    })

    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [], configurable: true })
    await input.trigger('change')

    expect(wrapper.emitted('update:value')).toBeFalsy()
  })
})
