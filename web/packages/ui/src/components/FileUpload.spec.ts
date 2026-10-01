import { describe, it, expect } from 'vitest'
import { shallowMount } from '../test-helpers'
import { defineComponent } from 'vue'
import FileUpload from './FileUpload.vue'

const IconStub = defineComponent({ props: ['name', 'size', 'color'], template: '<span />' })

function mountUpload(props: Record<string, unknown> = {}) {
  return shallowMount(FileUpload, {
    props,
    global: { stubs: { Icon: IconStub } },
  })
}

describe('FileUpload', () => {
  it('renders dropzone', () => {
    const w = mountUpload()
    expect(w.find('.dropzone').exists()).toBe(true)
  })

  it('renders label when provided', () => {
    const w = mountUpload({ label: 'Upload File' })
    expect(w.find('.file-upload-label').text()).toBe('Upload File')
  })

  it('hides label when not provided', () => {
    const w = mountUpload()
    expect(w.find('.file-upload-label').exists()).toBe(false)
  })

  it('shows accept hint', () => {
    const w = mountUpload({ accept: '.png,.jpg' })
    expect(w.find('.dropzone-hint').text()).toBe('.png,.jpg')
  })

  it('hides accept hint when not provided', () => {
    const w = mountUpload()
    expect(w.find('.dropzone-hint').exists()).toBe(false)
  })

  it('emits files on input change', async () => {
    const w = mountUpload()
    const file = new File(['content'], 'test.txt', { type: 'text/plain' })
    const input = w.find('.file-upload-hidden')
    // Simulate file input change by using the component's internal handler
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    const emitted = w.emitted('files')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toHaveLength(1)
  })

  it('applies disabled state', () => {
    const w = mountUpload({ disabled: true })
    expect(w.find('.file-upload-root').classes()).toContain('disabled')
  })

  it('renders browse text', () => {
    const w = mountUpload()
    expect(w.find('.dropzone-link').text()).toBe('browse')
  })

  it('emits files on drop', async () => {
    const w = mountUpload()
    const file = new File(['data'], 'img.png', { type: 'image/png' })
    const dt = new DataTransfer()
    dt.items.add(file)
    await w.find('.dropzone').trigger('drop', { dataTransfer: dt })
    const emitted = w.emitted('files')
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0]).toHaveLength(1)
  })

  it('sets dragging class on dragenter', async () => {
    const w = mountUpload()
    await w.find('.dropzone').trigger('dragenter')
    expect(w.find('.dropzone').classes()).toContain('dragging')
  })

  it('removes dragging class on dragleave', async () => {
    const w = mountUpload()
    await w.find('.dropzone').trigger('dragenter')
    await w.find('.dropzone').trigger('dragleave')
    expect(w.find('.dropzone').classes()).not.toContain('dragging')
  })

  it('removes dragging class after drop', async () => {
    const w = mountUpload()
    await w.find('.dropzone').trigger('dragenter')
    await w.find('.dropzone').trigger('drop', { dataTransfer: new DataTransfer() })
    expect(w.find('.dropzone').classes()).not.toContain('dragging')
  })

  it('does not emit when drop has no files', async () => {
    const w = mountUpload()
    await w.find('.dropzone').trigger('drop', { dataTransfer: new DataTransfer() })
    expect(w.emitted('files')).toBeUndefined()
  })

  it('resets input value after file selection', async () => {
    const w = mountUpload()
    const file = new File(['content'], 'test.txt', { type: 'text/plain' })
    const input = w.find('.file-upload-hidden')
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    expect((input.element as HTMLInputElement).value).toBe('')
  })
})
